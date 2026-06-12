package io.conddo.core.domain;

import io.conddo.core.auth.LockableAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A platform user. Tenant-scoped (SUPER_ADMIN lives in the sentinel platform
 * tenant), so every row carries tenant_id and is protected by the
 * {@code tenant_isolation} RLS policy enabled in V2.
 *
 * <p>The entity holds the lockout counters but not the lockout <em>policy</em>
 * (threshold, backoff) — that lives in the auth service, which calls the
 * mutators here.
 */
@Entity
@Table(name = "users")
public class User implements LockableAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(nullable = false)
    private String role = "TENANT_ADMIN";

    /**
     * Sub-role for STAFF users (Manager / Pharmacist / Cashier /
     * Stock Manager / Bookkeeper). Null for TENANT_ADMIN and
     * SUPER_ADMIN. The pair invariant is enforced by a DB CHECK
     * (V48), so we never have to defend it in app code.
     */
    @Column(name = "staff_role", length = 20)
    private String staffRole;

    private String phone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    /** Google's immutable subject id once the account is linked (§1a). */
    @Column(name = "google_sub")
    private String googleSub;

    /** Last-seen email from the Google ID token (informational only — Google can change it). */
    @Column(name = "google_email")
    private String googleEmail;

    @Column(name = "google_linked_at")
    private OffsetDateTime googleLinkedAt;

    protected User() {
    }

    public User(UUID tenantId, String email, String passwordHash, String fullName, String role, String phone) {
        this(tenantId, email, passwordHash, fullName, role, phone, null);
    }

    /** Full constructor — used during signup-with-Google so the link is written atomically. */
    public User(UUID tenantId, String email, String passwordHash, String fullName, String role, String phone,
                String googleSub) {
        this.tenantId = tenantId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.phone = phone;
        if (googleSub != null && !googleSub.isBlank()) {
            this.googleSub = googleSub;
            this.googleEmail = email;
            this.googleLinkedAt = OffsetDateTime.now();
        }
    }

    /** True if the account is locked at {@code now} (lockout window not yet elapsed). */
    public boolean isLocked(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Records a failed login attempt; the service decides when to {@link #lockUntil}. */
    public void recordFailedLogin() {
        this.failedLoginAttempts++;
    }

    public void lockUntil(OffsetDateTime until) {
        this.lockedUntil = until;
    }

    /** Clears the failure counter and any active lock — call on a successful login. */
    public void recordSuccessfulLogin(OffsetDateTime at) {
        this.lastLoginAt = at;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    /** Changes the user's role (§11.10 staff management). */
    public void changeRole(String role) {
        if (role != null && !role.isBlank()) {
            this.role = role;
        }
    }

    /** Changes the staff sub-role. Null is valid (clears the sub-role for owners). */
    public void changeStaffRole(String staffRole) {
        this.staffRole = staffRole;
    }

    public String getStaffRole() {
        return staffRole;
    }

    /** Activates or deactivates the account (§11.10). A deactivated user cannot log in. */
    public void setActive(boolean active) {
        this.active = active;
    }

    public void markPhoneVerified() {
        this.phoneVerified = true;
    }

    /**
     * Link a Google account to this user. Idempotent — re-linking with the same
     * {@code sub} just refreshes the cached email and link timestamp; a different
     * {@code sub} replaces the link (the spec lets a user move their Google
     * login between Google accounts as long as the new {@code sub} isn't already
     * claimed elsewhere — uniqueness is enforced by the DB index).
     */
    public void linkGoogle(String sub, String email, OffsetDateTime at) {
        this.googleSub = sub;
        this.googleEmail = email;
        this.googleLinkedAt = at;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public String getGoogleEmail() {
        return googleEmail;
    }

    public OffsetDateTime getGoogleLinkedAt() {
        return googleLinkedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRole() {
        return role;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
