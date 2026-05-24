package io.conddo.core.auth;

import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the account-lockout state machine deterministically — no database,
 * no Docker, and the password hasher is mocked (so this stays fast; real BCrypt
 * round-tripping is covered by {@link PasswordHasherTest} and the integration
 * test). The clock is a fixed instant, so the threshold and exponential backoff
 * are asserted exactly.
 */
class AuthServiceLockoutTest {

    private static final String SLUG = "amaka-styles";
    private static final String EMAIL = "owner@amaka.test";
    private static final String CORRECT = "correct horse battery staple";
    private static final int THRESHOLD = 5;
    private static final Duration BASE_LOCK = Duration.ofMinutes(15);

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC);
    private final OffsetDateTime now = OffsetDateTime.now(clock);

    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TenantSession tenantSession = mock(TenantSession.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final io.conddo.core.audit.AuditService auditService = mock(io.conddo.core.audit.AuditService.class);

    private final AuthProperties props =
            new AuthProperties(Duration.ofDays(30), THRESHOLD, BASE_LOCK, true, Duration.ofHours(1), "Strict");

    private AuthService authService;
    private User user;

    {
        // The only stubs needed: hashing yields a non-null value, and only the
        // CORRECT password verifies. Everything else returns Mockito's default false.
        when(hasher.hash(anyString())).thenReturn("HASH");
        when(hasher.matches(eq(CORRECT), anyString())).thenReturn(true);
        authService = new AuthService(tenantRepository, userRepository, tenantSession, hasher,
                jwtService, refreshTokenService, new LockoutPolicy(props), auditService,
                new io.conddo.core.registry.VerticalToolMatrix(), props, clock);
    }

    private User givenUser() {
        Tenant tenant = new Tenant("Amaka Styles", SLUG, "fashion", null);
        User u = new User(UUID.randomUUID(), EMAIL, "HASH", "Amaka", Role.TENANT_ADMIN.name(), null);
        when(tenantRepository.findBySlug(SLUG)).thenReturn(Optional.of(tenant));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(u));
        return u;
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void locksAfterThresholdConsecutiveFailures() {
        user = givenUser();

        for (int i = 0; i < THRESHOLD - 1; i++) {
            assertThrows(InvalidCredentialsException.class, () -> authService.login(SLUG, EMAIL, "wrong"));
        }
        assertEquals(THRESHOLD - 1, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil(), "should not be locked before the threshold");

        assertThrows(InvalidCredentialsException.class, () -> authService.login(SLUG, EMAIL, "wrong"));
        assertEquals(THRESHOLD, user.getFailedLoginAttempts());
        assertNotNull(user.getLockedUntil());
        assertEquals(now.plus(BASE_LOCK), user.getLockedUntil());
    }

    @Test
    void backoffDoublesPerFailureBeyondThreshold() {
        user = givenUser();
        for (int i = 0; i < THRESHOLD; i++) {
            assertThrows(InvalidCredentialsException.class, () -> authService.login(SLUG, EMAIL, "wrong"));
        }
        assertEquals(now.plus(BASE_LOCK), user.getLockedUntil());                 // 5th failure -> 15m

        // A locked account rejects even the correct password.
        assertThrows(AccountLockedException.class, () -> authService.login(SLUG, EMAIL, CORRECT));

        // Once the window elapses, the next failure doubles it (30m).
        user.lockUntil(now.minusSeconds(1));
        assertThrows(InvalidCredentialsException.class, () -> authService.login(SLUG, EMAIL, "wrong"));
        assertEquals(now.plus(BASE_LOCK.multipliedBy(2)), user.getLockedUntil());  // 6th failure -> 30m
    }

    @Test
    void unknownTenantAndUnknownUserBothFailAsInvalidCredentials() {
        when(tenantRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        assertThrows(InvalidCredentialsException.class, () -> authService.login("nope", EMAIL, CORRECT));

        when(tenantRepository.findBySlug(SLUG)).thenReturn(Optional.of(new Tenant("Amaka", SLUG, "fashion", null)));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        assertThrows(InvalidCredentialsException.class, () -> authService.login(SLUG, "ghost@x.test", CORRECT));
    }

    @Test
    void successfulLoginResetsCountersAndIssuesTokens() {
        user = givenUser();
        user.recordFailedLogin();
        user.recordFailedLogin();
        when(jwtService.issueAccessToken(any(), any(), anyString(), any(), any(), any()))
                .thenReturn("access.jwt.token");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenService.issue(any(), any())).thenReturn("selector.verifier");

        AuthResult result = authService.login(SLUG, EMAIL, CORRECT);

        assertEquals("access.jwt.token", result.accessToken());
        assertEquals("selector.verifier", result.refreshToken());
        assertEquals(0, user.getFailedLoginAttempts(), "counter resets on success");
        assertEquals(Role.TENANT_ADMIN.name(), result.role());
    }
}
