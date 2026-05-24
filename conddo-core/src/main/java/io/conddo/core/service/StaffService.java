package io.conddo.core.service;

import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.AuditLog;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.repository.AuditLogRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Staff management (§11.10): the tenant's users (TENANT_ADMIN / STAFF) over the
 * existing RLS-scoped {@code users} table — no new table. Admin-only. An invited
 * user is created without a usable password and sets one via the standard
 * password-reset flow (their email + business slug); deactivation is enforced at
 * login by {@code AuthService}. Per-user activity reads the audit log (§3).
 */
@Service
public class StaffService {

    /** Roles a tenant may assign to its own staff (not CUSTOMER / platform roles). */
    private static final Set<String> STAFF_ROLES = Set.of("TENANT_ADMIN", "STAFF");

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final TenantRepository tenantRepository;
    private final EmailSender emailSender;
    private final PasswordHasher passwordHasher;
    private final TenantSession tenantSession;

    public StaffService(UserRepository userRepository, AuditLogRepository auditLogRepository,
                        TenantRepository tenantRepository, EmailSender emailSender,
                        PasswordHasher passwordHasher, TenantSession tenantSession) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.tenantRepository = tenantRepository;
        this.emailSender = emailSender;
        this.passwordHasher = passwordHasher;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public List<User> list() {
        tenantSession.bind();
        return userRepository.findAll();
    }

    @Transactional
    public User invite(String email, String role) {
        tenantSession.bind();
        String resolvedRole = normaliseRole(role);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("A user with that email already exists");
        }
        // No usable password until the invitee sets one via the reset flow.
        String placeholderHash = passwordHasher.hash(UUID.randomUUID().toString());
        User user = userRepository.save(
                new User(TenantContext.require(), email, placeholderHash, null, resolvedRole, null));
        sendInvite(user);
        return user;
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        tenantSession.bind();
        return require(id);
    }

    @Transactional
    public User update(UUID id, String role, Boolean active) {
        tenantSession.bind();
        User user = require(id);
        if (role != null) {
            user.changeRole(normaliseRole(role));
        }
        if (active != null) {
            user.setActive(active);
        }
        return userRepository.save(user);
    }

    @Transactional
    public void resendInvite(UUID id) {
        tenantSession.bind();
        sendInvite(require(id));
    }

    @Transactional(readOnly = true)
    public List<AuditLog> activity(UUID id) {
        tenantSession.bind();
        require(id);
        return auditLogRepository.findTop50ByUserIdOrderByCreatedAtDesc(id);
    }

    /** Static role definitions + a plain-English permission summary (§11.10). */
    public List<RoleDef> roles() {
        return List.of(
                new RoleDef("TENANT_ADMIN", "Administrator",
                        List.of("Full access to all modules", "Manage settings, billing, and staff")),
                new RoleDef("STAFF", "Staff",
                        List.of("Manage customers, orders, bookings, and inventory",
                                "No access to settings, billing, or staff management")));
    }

    // ----- internals ----------------------------------------------------------

    private void sendInvite(User user) {
        Tenant tenant = tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
        String subject = "You're invited to " + tenant.getName() + " on Conddo";
        String body = "You've been added to " + tenant.getName() + " as " + user.getRole() + ".\n"
                + "Set your password using the 'Forgot password' option with your email ("
                + user.getEmail() + ") and business \"" + tenant.getSlug() + "\" to sign in.";
        emailSender.send(user.getEmail(), subject, body);
    }

    private String normaliseRole(String role) {
        String normalised = role == null ? "" : role.trim().toUpperCase();
        if (!STAFF_ROLES.contains(normalised)) {
            throw new IllegalArgumentException("Invalid staff role: " + role);
        }
        return normalised;
    }

    private User require(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
    }

    /** A role and a human-readable permission summary. */
    public record RoleDef(String role, String label, List<String> permissions) {
    }
}
