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

    protected User() {
    }

    public User(UUID tenantId, String email, String passwordHash, String fullName, String role, String phone) {
        this.tenantId = tenantId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.phone = phone;
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

    /** Activates or deactivates the account (§11.10). A deactivated user cannot log in. */
    public void setActive(boolean active) {
        this.active = active;
    }

    public void markPhoneVerified() {
        this.phoneVerified = true;
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
