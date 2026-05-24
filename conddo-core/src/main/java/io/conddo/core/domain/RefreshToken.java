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
 * A refresh token in the opaque "selector.verifier" scheme (PRD §6.2):
 * the client is given {@code <selector>.<verifier>}; we store the {@code selector}
 * for O(1) lookup and BCrypt({@code verifier}) in {@code token_hash} for
 * verification. Tokens rotate on every use; all tokens from one login share a
 * {@code familyId}, and presenting a revoked token revokes the whole family.
 *
 * <p>Not under tenant-isolation RLS by design (looked up unauthenticated during
 * refresh) — see V4__auth_grants.sql. The row carries its own tenant_id so
 * tenant scope is re-established once it is read.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(nullable = false)
    private String selector;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(UUID userId, UUID tenantId, UUID familyId,
                        String selector, String tokenHash, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.familyId = familyId;
        this.selector = selector;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** Usable for refresh: neither revoked nor expired. */
    public boolean isActive(OffsetDateTime now) {
        return !isRevoked() && !isExpired(now);
    }

    public void revoke(OffsetDateTime at, String reason) {
        if (this.revokedAt == null) {
            this.revokedAt = at;
            this.revokedReason = reason;
        }
    }

    /** Records the token this one was rotated into (set when revoking on rotation). */
    public void replacedBy(UUID newTokenId) {
        this.replacedBy = newTokenId;
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

    public UUID getFamilyId() {
        return familyId;
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

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
