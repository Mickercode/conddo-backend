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
 * An internal Handel Cores staff member (PRD v1.3 §22). NOT tenant-scoped — no
 * tenant_id, no RLS; email is globally unique. SUPER_ADMIN lives here; Conddo
 * Studio roles (Phase 3) will too. Authenticated through the same JWT machinery
 * as tenant users, but the token carries no tenant_id.
 */
@Entity
@Table(name = "staff_users")
public class StaffUser implements LockableAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "internal_role", nullable = false)
    private String internalRole;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected StaffUser() {
    }

    public StaffUser(String email, String passwordHash, String fullName, String internalRole) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.internalRole = internalRole;
    }

    @Override
    public boolean isLocked(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    @Override
    public void recordFailedLogin() {
        this.failedLoginAttempts++;
    }

    @Override
    public void lockUntil(OffsetDateTime until) {
        this.lockedUntil = until;
    }

    public void recordSuccessfulLogin(OffsetDateTime at) {
        this.lastLoginAt = at;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public UUID getId() {
        return id;
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

    public String getInternalRole() {
        return internalRole;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    @Override
    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
