package io.conddo.core.domain;

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
 * A single-use, short-lived password reset token. Same opaque
 * "selector.verifier" scheme as {@link RefreshToken}: {@code selector} is the
 * lookup key, {@code token_hash} is BCrypt(verifier).
 *
 * <p>Not under tenant-isolation RLS by design (consumed unauthenticated when the
 * user follows the reset link) — see V4__auth_grants.sql. The row carries its
 * own tenant_id so scope is re-established once read.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String selector;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PasswordResetToken() {
    }

    public PasswordResetToken(UUID userId, UUID tenantId, String selector,
                              String tokenHash, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.selector = selector;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** Consumable: neither already used nor expired. */
    public boolean isUsable(OffsetDateTime now) {
        return !isUsed() && !isExpired(now);
    }

    public void markUsed(OffsetDateTime at) {
        this.usedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSelector() {
        return selector;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
