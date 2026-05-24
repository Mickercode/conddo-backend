package io.conddo.core.auth;

import io.conddo.core.audit.AuditActions;
import io.conddo.core.audit.AuditService;
import io.conddo.core.domain.PasswordResetToken;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.repository.PasswordResetTokenRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Password reset (PRD §13.1). Uses the same opaque "selector.verifier" token as
 * refresh tokens; the reset token table is not RLS-scoped (consumed
 * unauthenticated) so it is looked up by selector and carries its own tenant_id.
 */
@Service
public class PasswordResetService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TenantSession tenantSession;
    private final PasswordHasher passwordHasher;
    private final RefreshTokenService refreshTokenService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AuthProperties properties;
    private final Clock clock;

    public PasswordResetService(TenantRepository tenantRepository, UserRepository userRepository,
                                PasswordResetTokenRepository passwordResetTokenRepository,
                                TenantSession tenantSession, PasswordHasher passwordHasher,
                                RefreshTokenService refreshTokenService, NotificationService notificationService,
                                AuditService auditService, AuthProperties properties, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.tenantSession = tenantSession;
        this.passwordHasher = passwordHasher;
        this.refreshTokenService = refreshTokenService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Begins a reset. Returns silently whether or not the account exists (no
     * enumeration); if it exists, a single-use token is created and delivered
     * out-of-band via the {@link NotificationService}.
     */
    @Transactional
    public void requestReset(String tenantSlug, String email) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug).orElse(null);
        if (tenant == null) {
            return;
        }
        TenantContext.set(tenant.getId());
        tenantSession.bind();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return;
        }

        String selector = OpaqueToken.randomBase64Url(OpaqueToken.SELECTOR_BYTES);
        String verifier = OpaqueToken.randomBase64Url(OpaqueToken.VERIFIER_BYTES);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(properties.passwordResetTtl());
        passwordResetTokenRepository.save(new PasswordResetToken(
                user.getId(), tenant.getId(), selector, passwordHasher.hash(verifier), expiresAt));

        notificationService.sendPasswordReset(user.getEmail(), selector + OpaqueToken.SEPARATOR + verifier);
    }

    /**
     * Completes a reset: validates the single-use token, sets the new password,
     * marks the token used, and revokes all of the user's refresh tokens — a
     * password change ends every existing session.
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        PasswordResetToken token = resolve(rawToken);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!token.isUsable(now)) {
            throw new InvalidPasswordResetTokenException();
        }

        TenantContext.set(token.getTenantId());
        tenantSession.bind();
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(InvalidPasswordResetTokenException::new);

        user.changePassword(passwordHasher.hash(newPassword));
        userRepository.save(user);
        token.markUsed(now);
        passwordResetTokenRepository.save(token);
        refreshTokenService.revokeAllForUser(user.getId(), "password-reset");
        auditService.record(AuditActions.PASSWORD_RESET, "USER", user.getId(),
                token.getTenantId(), user.getId(), null, null);
    }

    private PasswordResetToken resolve(String rawToken) {
        if (rawToken == null) {
            throw new InvalidPasswordResetTokenException();
        }
        int sep = rawToken.indexOf(OpaqueToken.SEPARATOR);
        if (sep <= 0 || sep == rawToken.length() - 1) {
            throw new InvalidPasswordResetTokenException();
        }
        String selector = rawToken.substring(0, sep);
        String verifier = rawToken.substring(sep + 1);

        PasswordResetToken token = passwordResetTokenRepository.findBySelector(selector)
                .orElseThrow(InvalidPasswordResetTokenException::new);
        if (!passwordHasher.matches(verifier, token.getTokenHash())) {
            throw new InvalidPasswordResetTokenException();
        }
        return token;
    }
}
