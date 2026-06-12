package io.conddo.core.auth;

import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.NotificationProperties;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.service.BillingService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Staff invite-token lifecycle (HANDOFF_2026-06-12 §5).
 *
 * <ul>
 *   <li>{@link #invite} — generate a single-use token, create the user
 *       row in INVITED status, email the accept link.</li>
 *   <li>{@link #previewInvite} — token → tenant name + role label so
 *       the FE can render "You've been invited to X as Y" before
 *       asking for a password.</li>
 *   <li>{@link #acceptInvite} — set the bcrypt'd password, flip the
 *       row to ACTIVE, issue an AuthResult so the FE drops straight
 *       into the work shell.</li>
 * </ul>
 */
@Service
public class StaffInviteService {

    private static final Logger log = LoggerFactory.getLogger(StaffInviteService.class);

    static final Set<String> VALID_STAFF_ROLES = Set.of(
            "MANAGER", "PHARMACIST", "CASHIER", "STOCK_MANAGER", "BOOKKEEPER");

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordHasher passwordHasher;
    private final TenantSession tenantSession;
    private final EmailSender emailSender;
    private final NotificationProperties notificationProperties;
    private final BillingService billingService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom rng = new SecureRandom();
    private final String appUrl;
    private final Duration inviteTtl;

    public StaffInviteService(UserRepository userRepository, TenantRepository tenantRepository,
                              PasswordHasher passwordHasher, TenantSession tenantSession,
                              EmailSender emailSender, NotificationProperties notificationProperties,
                              BillingService billingService,
                              JwtService jwtService, RefreshTokenService refreshTokenService,
                              AuthProperties properties, Clock clock,
                              @Value("${conddo.app-url:https://app.conddo.io}") String appUrl,
                              @Value("${conddo.staff.invite-ttl-hours:72}") long inviteTtlHours) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordHasher = passwordHasher;
        this.tenantSession = tenantSession;
        this.emailSender = emailSender;
        this.notificationProperties = notificationProperties;
        this.billingService = billingService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.properties = properties;
        this.clock = clock;
        this.appUrl = appUrl;
        this.inviteTtl = Duration.ofHours(inviteTtlHours);
    }

    // ----- invite ------------------------------------------------------------

    @Transactional
    public User invite(String email, String staffRole, String fullName, UUID invitedByUserId) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        String resolvedRole = normaliseStaffRole(staffRole);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new EmailAlreadyInUseException(email);
        }
        // Plan-tier cap (BILLING_TIERS_SPEC §5; HANDOFF_2026-06-12 §5).
        int currentStaff = userRepository.findAll().size();
        int limit = billingService.featureLimit(tenantId, "staff_accounts");
        if (limit > 0 && limit != Integer.MAX_VALUE && currentStaff >= limit) {
            throw new PlanLimitReachedException(limit);
        }

        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(inviteTtl);

        // Placeholder password — must be replaced via /auth/accept-invite.
        // Hash of a fresh UUID, not a known value, so brute-forcing is moot.
        String placeholderHash = passwordHasher.hash(UUID.randomUUID().toString());
        User user = new User(tenantId, email, placeholderHash, fullName, "STAFF", null);
        user.changeStaffRole(resolvedRole);
        user.markInvited(tokenHash, expiresAt, invitedByUserId);
        user = userRepository.save(user);

        sendInviteEmail(user, rawToken);
        return user;
    }

    @Transactional
    public User resendInvite(UUID userId) {
        tenantSession.bind();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InviteInvalidException("Staff member not found"));
        if (!User.STATUS_INVITED.equals(user.getStatus())) {
            // Already accepted — re-send is a no-op rather than an error.
            log.info("resendInvite called on non-INVITED user {}: no-op", userId);
            return user;
        }
        String rawToken = generateToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(inviteTtl);
        user.markInvited(sha256Hex(rawToken), expiresAt, user.getInvitedByUserId());
        userRepository.save(user);
        sendInviteEmail(user, rawToken);
        return user;
    }

    // ----- preview -----------------------------------------------------------

    @Transactional(readOnly = true)
    public InvitePreview previewInvite(String rawToken) {
        User user = resolveInvitee(rawToken);
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new InviteInvalidException("Tenant not found"));
        String invitedByName = null;
        if (user.getInvitedByUserId() != null) {
            // Cross-tenant lookup of the inviter — bound to the invitee's tenant
            // already, since the inviter is the same tenant.
            tenantSession.bindCrossTenant();
            invitedByName = userRepository.findById(user.getInvitedByUserId())
                    .map(User::getFullName).orElse(null);
        }
        return new InvitePreview(tenant.getName(), labelFor(user.getStaffRole()),
                user.getStaffRole(), user.getEmail(), invitedByName);
    }

    // ----- accept ------------------------------------------------------------

    @Transactional
    public AuthResult acceptInvite(String rawToken, String rawPassword, String fullName) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        User user = resolveInvitee(rawToken);
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new InviteInvalidException("Tenant not found"));

        TenantContext.set(user.getTenantId());
        tenantSession.bind();

        user.acceptInvite(passwordHasher.hash(rawPassword), fullName);
        OffsetDateTime now = OffsetDateTime.now(clock);
        user.recordSuccessfulLogin(now);
        userRepository.save(user);

        String accessToken = jwtService.issueAccessToken(user.getId(), user.getTenantId(),
                user.getRole(), user.getStaffRole(),
                tenant.getVerticalId(), tenant.getPlanId(), java.util.List.of());
        String refreshToken = refreshTokenService.issue(user.getId(), user.getTenantId());
        return new AuthResult(accessToken, jwtService.accessTokenTtl(),
                refreshToken, properties.refreshTokenTtl(), user.getId(), user.getRole());
    }

    // ----- internals ---------------------------------------------------------

    private User resolveInvitee(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InviteInvalidException("Token is required");
        }
        // Cross-tenant lookup — the caller has no JWT at this point.
        tenantSession.bindCrossTenant();
        Optional<User> match = userRepository.findByInviteTokenHashCrossTenant(sha256Hex(rawToken));
        User user = match.orElseThrow(() -> new InviteInvalidException("Invite not found"));
        if (!User.STATUS_INVITED.equals(user.getStatus())) {
            throw new InviteInvalidException("Invite already used");
        }
        if (user.getInviteTokenExpiresAt() == null
                || user.getInviteTokenExpiresAt().isBefore(OffsetDateTime.now(clock))) {
            throw new InviteExpiredException();
        }
        return user;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        rng.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashed = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static String normaliseStaffRole(String staffRole) {
        String normalised = staffRole == null ? "" : staffRole.trim().toUpperCase();
        if (!VALID_STAFF_ROLES.contains(normalised)) {
            throw new IllegalArgumentException("Invalid staff role: " + staffRole);
        }
        return normalised;
    }

    private static String labelFor(String staffRole) {
        return switch (staffRole) {
            case "MANAGER" -> "Manager";
            case "PHARMACIST" -> "Pharmacist";
            case "CASHIER" -> "Cashier";
            case "STOCK_MANAGER" -> "Stock Manager";
            case "BOOKKEEPER" -> "Bookkeeper";
            default -> staffRole;
        };
    }

    private void sendInviteEmail(User user, String rawToken) {
        try {
            Tenant tenant = tenantRepository.findById(user.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("Tenant vanished"));
            String acceptUrl = appUrl + "/accept-invite?token=" + rawToken;
            String roleLabel = labelFor(user.getStaffRole());
            String subject = tenant.getName() + " invited you to join as " + roleLabel;
            String fallbackBody = inlineFallbackBody(user, tenant, roleLabel, acceptUrl);

            Long templateId = templateId();
            if (templateId == null) {
                emailSender.send(user.getEmail(), subject, fallbackBody);
                return;
            }
            String invitedByName = user.getInvitedByUserId() == null ? null
                    : userRepository.findById(user.getInvitedByUserId())
                            .map(User::getFullName).orElse(null);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("firstName", firstName(user.getFullName()));
            params.put("tenantName", tenant.getName());
            params.put("roleLabel", roleLabel);
            params.put("roleAccessLines", roleAccessLines(user.getStaffRole()));
            params.put("invitedByName", invitedByName);
            params.put("acceptUrl", acceptUrl);
            params.put("expiresAt",
                    OffsetDateTime.now(clock).plus(inviteTtl).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            emailSender.sendTemplate(user.getEmail(), templateId, params, subject, fallbackBody);
        } catch (RuntimeException ex) {
            // Never let an email outage block the invite — the row is
            // saved, the admin can resend.
            log.warn("Invite email failed for {}: {}", user.getEmail(), ex.getMessage());
        }
    }

    private Long templateId() {
        NotificationProperties.Email email = notificationProperties == null
                ? null : notificationProperties.email();
        NotificationProperties.Templates templates = email == null ? null : email.templates();
        return templates == null ? null : templates.invite();
    }

    private static String inlineFallbackBody(User user, Tenant tenant, String roleLabel, String acceptUrl) {
        return "Hi" + (user.getFullName() == null ? "" : " " + user.getFullName()) + ",\n\n"
                + "You've been invited to join " + tenant.getName()
                + " on Conddo as a " + roleLabel + ".\n\n"
                + "Accept the invite and set your password here:\n"
                + acceptUrl + "\n\n"
                + "This link expires in 72 hours.\n";
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        String first = fullName.trim().split("\\s+")[0];
        return first.isEmpty() ? "there" : first;
    }

    /**
     * Plain-English access lines per sub-role — passed as a list so
     * the Brevo template can render them as bullets. Keep in sync
     * with {@code StaffService.roles().permissions}.
     */
    private static List<String> roleAccessLines(String staffRole) {
        return switch (staffRole == null ? "" : staffRole) {
            case "MANAGER" -> List.of(
                    "Everything except billing + staff invites",
                    "Inventory, sales, orders, customers, analytics");
            case "PHARMACIST" -> List.of(
                    "Clinical access: EMR, prescriptions, consultations",
                    "Read-only inventory, orders, customers, analytics");
            case "CASHIER" -> List.of(
                    "POS sales (open shifts, run sales, take payments)",
                    "Read-only customers, orders, payments, inventory");
            case "STOCK_MANAGER" -> List.of(
                    "Inventory: restock, reconciliation, bulk upload, movement log",
                    "Read-only orders, customers, analytics");
            case "BOOKKEEPER" -> List.of(
                    "Read-only orders, payments, analytics, customers",
                    "CSV exports for reconciliation");
            default -> List.of();
        };
    }

    // ----- DTOs --------------------------------------------------------------

    public record InvitePreview(String tenantName, String roleLabel, String staffRole,
                                 String email, String invitedByName) {
    }

    // ----- domain exceptions -------------------------------------------------

    public static class EmailAlreadyInUseException extends RuntimeException {
        public EmailAlreadyInUseException(String email) {
            super("A user with email " + email + " already exists");
        }
    }

    public static class PlanLimitReachedException extends RuntimeException {
        private final int limit;

        public PlanLimitReachedException(int limit) {
            super("Staff seat limit reached (" + limit + "). Upgrade your plan to invite more staff.");
            this.limit = limit;
        }

        public int getLimit() {
            return limit;
        }
    }

    public static class InviteInvalidException extends RuntimeException {
        public InviteInvalidException(String message) {
            super(message);
        }
    }

    public static class InviteExpiredException extends RuntimeException {
        public InviteExpiredException() {
            super("Invite token has expired");
        }
    }
}
