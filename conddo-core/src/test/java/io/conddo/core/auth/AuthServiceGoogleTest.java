package io.conddo.core.auth;

import io.conddo.core.audit.AuditService;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.registry.VerticalToolMatrix;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService Google Sign-in flow (ACTION_LIST §1a). Covers the verifier's
 * three error paths (invalid token, unverified email, no user match), the
 * happy paths (linked user, first-time link via email), and the integrity
 * guards (locked / inactive accounts must not slip through).
 */
class AuthServiceGoogleTest {

    private static final String SLUG = "amaka-styles";
    private static final String EMAIL = "owner@amaka.com";
    private static final String GOOGLE_SUB = "google-sub-12345";
    private static final String ID_TOKEN = "fake.id.token";

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TenantSession tenantSession = mock(TenantSession.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final GoogleIdTokenVerifier googleVerifier = mock(GoogleIdTokenVerifier.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-03T12:00:00Z"), ZoneOffset.UTC);
    private final AuthProperties props =
            new AuthProperties(Duration.ofDays(30), 5, Duration.ofMinutes(15), true, Duration.ofHours(1), "Strict");

    private AuthService authService;

    @BeforeEach
    void setup() {
        when(hasher.hash(anyString())).thenReturn("HASH");
        when(jwtService.issueAccessToken(any(), any(), anyString(), any(), anyString(), anyString(), any()))
                .thenReturn("access.jwt");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenService.issue(any(), any())).thenReturn("refresh-raw");
        authService = new AuthService(tenantRepository, userRepository, tenantSession, hasher,
                jwtService, refreshTokenService, new LockoutPolicy(props), auditService,
                new VerticalToolMatrix(), props, googleVerifier, clock);
    }

    @Test
    void invalidIdTokenSurfacesAs400Equivalent() {
        when(googleVerifier.verify(ID_TOKEN)).thenReturn(Optional.empty());
        assertThrows(GoogleIdTokenInvalidException.class,
                () -> authService.loginWithGoogle(SLUG, ID_TOKEN));
        verify(tenantRepository, never()).findBySlug(anyString());
    }

    @Test
    void unverifiedEmailIsRejected() {
        when(googleVerifier.verify(ID_TOKEN)).thenReturn(Optional.of(
                new GoogleIdentity(GOOGLE_SUB, EMAIL, false, "Amaka")));
        assertThrows(GoogleEmailUnverifiedException.class,
                () -> authService.loginWithGoogle(SLUG, ID_TOKEN));
        verify(tenantRepository, never()).findBySlug(anyString());
    }

    @Test
    void unknownTenantIs404UserNotFound() {
        when(googleVerifier.verify(ID_TOKEN)).thenReturn(Optional.of(
                new GoogleIdentity(GOOGLE_SUB, EMAIL, true, "Amaka")));
        when(tenantRepository.findBySlug(SLUG)).thenReturn(Optional.empty());
        // Same 404 whether the tenant or the user is missing — anti-enumeration.
        assertThrows(UserNotFoundException.class,
                () -> authService.loginWithGoogle(SLUG, ID_TOKEN));
    }

    @Test
    void linkedUserSignsInSuccessfully() {
        givenVerified();
        Tenant tenant = givenTenant();
        User user = userWith(tenant, EMAIL, GOOGLE_SUB);
        when(userRepository.findByGoogleSub(GOOGLE_SUB)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        AuthResult result = authService.loginWithGoogle(SLUG, ID_TOKEN);

        assertNotNull(result.accessToken());
        assertEquals(user.getId(), result.userId());
        ArgumentCaptor<Map<String, Object>> after = captureAuditAfter();
        assertEquals("google", after.getValue().get("method"));
    }

    @Test
    void firstTimeSigninLinksTheGoogleSubToExistingEmailUser() {
        givenVerified();
        Tenant tenant = givenTenant();
        User user = userWith(tenant, EMAIL, null);
        when(userRepository.findByGoogleSub(GOOGLE_SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        authService.loginWithGoogle(SLUG, ID_TOKEN);

        assertEquals(GOOGLE_SUB, user.getGoogleSub(),
                "first-time Google sign-in should atomically link the sub onto the existing user row");
    }

    @Test
    void unlinkedUserWithDifferentEmail404s() {
        givenVerified();
        givenTenant();
        when(userRepository.findByGoogleSub(GOOGLE_SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> authService.loginWithGoogle(SLUG, ID_TOKEN));
    }

    @Test
    void alreadyLinkedToDifferentGoogleSubIsNotImplicitlyOverwritten() {
        givenVerified();
        Tenant tenant = givenTenant();
        User user = userWith(tenant, EMAIL, "different-sub");
        when(userRepository.findByGoogleSub(GOOGLE_SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));   // returned but filtered out

        // The filter inside loginWithGoogle requires googleSub == null on the email match;
        // a user already linked to a different sub must NOT be silently re-linked.
        assertThrows(UserNotFoundException.class,
                () -> authService.loginWithGoogle(SLUG, ID_TOKEN));
        assertEquals("different-sub", user.getGoogleSub(), "existing link must not be overwritten");
    }

    @Test
    void inactiveAccountIs404NotEnumerable() {
        givenVerified();
        Tenant tenant = givenTenant();
        User user = userWith(tenant, EMAIL, GOOGLE_SUB);
        user.setActive(false);
        when(userRepository.findByGoogleSub(GOOGLE_SUB)).thenReturn(Optional.of(user));

        assertThrows(UserNotFoundException.class,
                () -> authService.loginWithGoogle(SLUG, ID_TOKEN));
    }

    @Test
    void lockedAccountSurfacesAsAuthAccountLocked() {
        givenVerified();
        Tenant tenant = givenTenant();
        User user = userWith(tenant, EMAIL, GOOGLE_SUB);
        user.lockUntil(java.time.OffsetDateTime.now(clock).plusHours(1));
        when(userRepository.findByGoogleSub(GOOGLE_SUB)).thenReturn(Optional.of(user));

        assertThrows(AccountLockedException.class,
                () -> authService.loginWithGoogle(SLUG, ID_TOKEN));
    }

    // ----- helpers ------------------------------------------------------------

    private void givenVerified() {
        when(googleVerifier.verify(ID_TOKEN)).thenReturn(Optional.of(
                new GoogleIdentity(GOOGLE_SUB, EMAIL, true, "Amaka")));
    }

    private Tenant givenTenant() {
        Tenant tenant = new Tenant("Amaka Styles", SLUG, "fashion", null);
        setId(Tenant.class, tenant, "id", UUID.randomUUID());
        when(tenantRepository.findBySlug(SLUG)).thenReturn(Optional.of(tenant));
        return tenant;
    }

    private User userWith(Tenant tenant, String email, String googleSub) {
        User user = new User(tenant.getId(), email, "HASH", "Amaka",
                Role.TENANT_ADMIN.name(), null, googleSub);
        setId(User.class, user, "id", UUID.randomUUID());
        return user;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> captureAuditAfter() {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(io.conddo.core.audit.AuditActions.LOGIN), anyString(),
                any(), any(), any(), any(), after.capture());
        return after;
    }

    private static void setId(Class<?> type, Object target, String name, Object value) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
