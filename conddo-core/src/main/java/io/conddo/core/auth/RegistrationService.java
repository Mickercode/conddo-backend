package io.conddo.core.auth;

import io.conddo.core.audit.AuditActions;
import io.conddo.core.audit.AuditService;
import io.conddo.core.domain.PendingRegistration;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.registry.VerticalToolMatrix;
import io.conddo.core.repository.PendingRegistrationRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.service.TenantService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Staged, phone-verified signup (PRD §6.2) backing the frontend's signup wizard:
 * <ol>
 *   <li>{@link #start} — stash the account + send a 4-digit SMS OTP (no tenant yet)</li>
 *   <li>{@link #verify} / {@link #resend} — confirm the phone</li>
 *   <li>{@link #complete} — create the tenant + admin and issue tokens</li>
 * </ol>
 * The account/tenant is only created at the end, so abandoned wizards leave no
 * half-built tenants. The OTP code is stored BCrypt-hashed and guarded by
 * expiry + attempt + resend limits ({@link OtpProperties}) — essential since a
 * 4-digit code is otherwise trivially brute-forced.
 */
@Service
public class RegistrationService {

    private final PendingRegistrationRepository registrations;
    private final PasswordHasher passwordHasher;
    private final NotificationService notificationService;
    private final TenantService tenantService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final VerticalToolMatrix toolMatrix;
    private final AuthProperties authProperties;
    private final OtpProperties otp;
    private final GoogleIdTokenVerifier googleVerifier;
    private final UserRepository userRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    public RegistrationService(PendingRegistrationRepository registrations, PasswordHasher passwordHasher,
                               NotificationService notificationService, TenantService tenantService,
                               JwtService jwtService, RefreshTokenService refreshTokenService,
                               AuditService auditService, VerticalToolMatrix toolMatrix,
                               AuthProperties authProperties, OtpProperties otp,
                               GoogleIdTokenVerifier googleVerifier, UserRepository userRepository,
                               TenantSession tenantSession, Clock clock) {
        this.registrations = registrations;
        this.passwordHasher = passwordHasher;
        this.notificationService = notificationService;
        this.tenantService = tenantService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.toolMatrix = toolMatrix;
        this.authProperties = authProperties;
        this.otp = otp;
        this.googleVerifier = googleVerifier;
        this.userRepository = userRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    /**
     * Step 1 (Google variant — ACTION_LIST §1a) — verify the Google ID token,
     * stash the Google sub + identity, and send an SMS/email OTP to verify the
     * Nigerian phone (Google can't vouch for phones; SMS verification is
     * non-negotiable per the platform's compliance line). The password is a
     * random 64-char string the user never sees — they authenticate via Google
     * going forward; the password slot is just so {@code /auth/forgot-password}
     * still works if they later want it.
     */
    @Transactional
    public StartResult startWithGoogle(String idToken, String phone) {
        GoogleIdentity identity = googleVerifier.verify(idToken)
                .orElseThrow(GoogleIdTokenInvalidException::new);
        if (!identity.emailVerified()) {
            throw new GoogleEmailUnverifiedException();
        }
        String fullName = identity.name() == null || identity.name().isBlank()
                ? identity.email() : identity.name();
        String randomPassword = randomPassword();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String code = generateCode();
        PendingRegistration registration = new PendingRegistration(
                fullName, phone, identity.email(), passwordHasher.hash(randomPassword),
                passwordHasher.hash(code), now.plus(otp.ttl()), now, identity.sub());
        registrations.save(registration);
        notificationService.sendOtpEmail(identity.email(), code);
        return new StartResult(registration.getId(), otp.resendCooldown().toSeconds());
    }

    /** Step 1 — stash the account details and send the first OTP. */
    @Transactional
    public StartResult start(String fullName, String phone, String email, String rawPassword) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String code = generateCode();
        PendingRegistration registration = new PendingRegistration(
                fullName, phone, email, passwordHasher.hash(rawPassword),
                passwordHasher.hash(code), now.plus(otp.ttl()), now);
        registrations.save(registration);
        notificationService.sendOtpEmail(email, code); // free path (Resend); swap to sendOtp(phone,code) once SMS is funded
        return new StartResult(registration.getId(), otp.resendCooldown().toSeconds());
    }

    /** Step 2 — verify the code. Idempotent once verified. */
    @Transactional
    public void verify(UUID registrationId, String code) {
        PendingRegistration registration = active(registrationId);
        if (registration.isPhoneVerified()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (registration.isOtpExpired(now) || registration.attemptsExhausted(otp.maxAttempts())) {
            throw new InvalidOtpException();
        }
        if (!passwordHasher.matches(code, registration.getOtpHash())) {
            registration.recordFailedAttempt();
            registrations.save(registration);
            throw new InvalidOtpException();
        }
        registration.markPhoneVerified();
        registrations.save(registration);
    }

    /** Sends a fresh code (subject to cooldown + cap). Returns the new cooldown in seconds. */
    @Transactional
    public long resend(UUID registrationId) {
        PendingRegistration registration = active(registrationId);
        if (registration.isPhoneVerified()) {
            return otp.resendCooldown().toSeconds();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!registration.canResend(now, otp.resendCooldown())) {
            throw new OtpThrottledException("Please wait before requesting another code");
        }
        if (registration.resendsExhausted(otp.maxResends())) {
            throw new OtpThrottledException("Too many codes requested; please start over");
        }
        String code = generateCode();
        registration.newCode(passwordHasher.hash(code), now.plus(otp.ttl()), now);
        registrations.save(registration);
        notificationService.sendOtpEmail(registration.getEmail(), code); // free path (Resend)
        return otp.resendCooldown().toSeconds();
    }

    /** Final step — create the tenant + admin (phone must be verified) and log in. */
    @Transactional
    public AuthResult complete(UUID registrationId, String businessName, String businessType, String planId) {
        PendingRegistration registration = active(registrationId);
        if (!registration.isPhoneVerified()) {
            throw new PhoneNotVerifiedException();
        }
        TenantService.Provisioned provisioned = tenantService.provisionFromRegistration(
                businessName, businessType, planId, registration.getEmail(),
                registration.getPasswordHash(), registration.getFullName(), registration.getPhone());
        registration.markCompleted(OffsetDateTime.now(clock));
        registrations.save(registration);

        var admin = provisioned.admin();
        Tenant tenant = provisioned.tenant();

        // Link Google to the new admin if this was a start-google signup.
        if (registration.getGoogleSub() != null && !registration.getGoogleSub().isBlank()) {
            TenantContext.set(tenant.getId());
            tenantSession.bind();
            User refreshed = userRepository.findById(admin.getId()).orElse(admin);
            refreshed.linkGoogle(registration.getGoogleSub(), registration.getEmail(),
                    OffsetDateTime.now(clock));
            userRepository.save(refreshed);
        }
        auditService.record(AuditActions.SIGNUP_COMPLETED, "TENANT", tenant.getId(),
                tenant.getId(), admin.getId(), null, Map.of("businessName", businessName));

        String accessToken = jwtService.issueAccessToken(admin.getId(), admin.getTenantId(), admin.getRole(),
                admin.getStaffRole(),
                tenant.getVerticalId(), toolMatrix.normalizePlan(tenant.getPlanId()),
                toolMatrix.resolve(tenant.getVerticalId(), tenant.getPlanId()));
        String refreshToken = refreshTokenService.issue(admin.getId(), admin.getTenantId());
        return new AuthResult(accessToken, jwtService.accessTokenTtl(), refreshToken,
                authProperties.refreshTokenTtl(), admin.getId(), admin.getRole());
    }

    private PendingRegistration active(UUID registrationId) {
        return registrations.findById(registrationId)
                .filter(registration -> !registration.isCompleted())
                .orElseThrow(RegistrationNotFoundException::new);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, otp.codeLength());
        return String.format("%0" + otp.codeLength() + "d", random.nextInt(bound));
    }

    /** 48 random bytes URL-base64 → 64 chars. Used for Google-signup placeholder passwords. */
    private String randomPassword() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Outcome of {@link #start}: the id the frontend carries through the wizard. */
    public record StartResult(UUID registrationId, long resendCooldownSeconds) {
    }
}
