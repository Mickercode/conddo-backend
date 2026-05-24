package io.conddo.core.auth;

import io.conddo.core.audit.AuditActions;
import io.conddo.core.audit.AuditService;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.registry.VerticalToolMatrix;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticates users and issues tokens (PRD §6.2 / §12.1).
 *
 * <p>Login is tenant-scoped: {@code users.email} is unique only per tenant, and
 * {@code users} is RLS-protected, so we resolve the tenant (by slug, against the
 * un-scoped {@code tenants} table), bind it to the transaction, then look the
 * user up. Failures for unknown tenant / unknown email / wrong password / inactive
 * account all surface identically ({@link InvalidCredentialsException}) and run an
 * equal-cost BCrypt check, so the response neither confirms account existence nor
 * leaks timing.
 */
@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantSession tenantSession;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LockoutPolicy lockoutPolicy;
    private final AuditService auditService;
    private final VerticalToolMatrix toolMatrix;
    private final AuthProperties properties;
    private final Clock clock;

    /** A real BCrypt hash, matched against when no user is found, to equalise timing. */
    private final String timingEqualiserHash;

    public AuthService(TenantRepository tenantRepository, UserRepository userRepository,
                       TenantSession tenantSession, PasswordHasher passwordHasher, JwtService jwtService,
                       RefreshTokenService refreshTokenService, LockoutPolicy lockoutPolicy,
                       AuditService auditService, VerticalToolMatrix toolMatrix,
                       AuthProperties properties, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.tenantSession = tenantSession;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.lockoutPolicy = lockoutPolicy;
        this.auditService = auditService;
        this.toolMatrix = toolMatrix;
        this.properties = properties;
        this.clock = clock;
        this.timingEqualiserHash = passwordHasher.hash("timing-equaliser");
    }

    /** Issues an access token stamped with the tenant's vertical/plan/activeModules (§4.4). */
    private String issueAccessTokenFor(User user, Tenant tenant) {
        return jwtService.issueAccessToken(user.getId(), user.getTenantId(), user.getRole(),
                tenant.getVerticalId(), toolMatrix.normalizePlan(tenant.getPlanId()),
                toolMatrix.resolve(tenant.getVerticalId(), tenant.getPlanId()));
    }

    /**
     * {@code noRollbackFor} the auth exceptions is deliberate: on a failed login
     * we increment {@code failed_login_attempts} (and may set the lockout) and
     * MUST keep that even though we then throw — otherwise the counter rolls back
     * and lockout never triggers. (Audit rows persist regardless, via REQUIRES_NEW.)
     */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public AuthResult login(String tenantSlug, String email, String rawPassword) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug).orElse(null);
        if (tenant == null) {
            passwordHasher.matches(rawPassword, timingEqualiserHash);
            recordLoginFailure(null, null, email, "unknown_tenant");
            throw new InvalidCredentialsException();
        }

        // The user lookup is RLS-scoped — bind the resolved tenant first.
        TenantContext.set(tenant.getId());
        tenantSession.bind();
        User user = userRepository.findByEmail(email).orElse(null);
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (user == null) {
            passwordHasher.matches(rawPassword, timingEqualiserHash);
            recordLoginFailure(tenant.getId(), null, email, "unknown_user");
            throw new InvalidCredentialsException();
        }
        if (user.isLocked(now)) {
            recordLoginFailure(tenant.getId(), user.getId(), email, "locked");
            throw new AccountLockedException(user.getLockedUntil());
        }
        if (!user.isActive() || !passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            if (user.isActive()) {
                lockoutPolicy.registerFailedAttempt(user, now);
                userRepository.save(user);
            }
            recordLoginFailure(tenant.getId(), user.getId(), email, user.isActive() ? "bad_credentials" : "inactive");
            throw new InvalidCredentialsException();
        }

        user.recordSuccessfulLogin(now);
        userRepository.save(user);

        String accessToken = issueAccessTokenFor(user, tenant);
        String refreshToken = refreshTokenService.issue(user.getId(), user.getTenantId());
        auditService.record(AuditActions.LOGIN, "USER", user.getId(), user.getTenantId(), user.getId(), null, null);
        return new AuthResult(accessToken, jwtService.accessTokenTtl(),
                refreshToken, properties.refreshTokenTtl(), user.getId(), user.getRole());
    }

    private void recordLoginFailure(UUID tenantId, UUID userId, String email, String reason) {
        auditService.record(AuditActions.LOGIN_FAILED, "USER", userId, tenantId, userId,
                null, Map.of("email", email, "reason", reason));
    }

    /**
     * Exchanges a valid refresh token for a fresh access token and a rotated
     * refresh token. Reuse of a revoked token is detected in
     * {@link RefreshTokenService} and kills the family. The user's <em>current</em>
     * role is read from the database, so role changes take effect on refresh, and
     * a deactivated account can no longer refresh.
     */
    @Transactional
    public AuthResult refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken);

        TenantContext.set(rotation.tenantId());
        tenantSession.bind();
        User user = userRepository.findById(rotation.userId())
                .filter(User::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);
        Tenant tenant = tenantRepository.findById(rotation.tenantId())
                .orElseThrow(InvalidRefreshTokenException::new);

        String accessToken = issueAccessTokenFor(user, tenant);
        return new AuthResult(accessToken, jwtService.accessTokenTtl(),
                rotation.rawToken(), properties.refreshTokenTtl(), user.getId(), user.getRole());
    }

    /** Logs out by revoking the presented refresh token's family. Idempotent. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeFamilyOf(rawRefreshToken);
    }
}
