package io.conddo.studio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A staff refresh token (jobs schema). Opaque random string, stored hashed. */
@Entity
@Table(schema = "jobs", name = "staff_refresh_tokens")
public class StaffRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected StaffRefreshToken() {
    }

    public StaffRefreshToken(UUID staffId, String tokenHash, OffsetDateTime expiresAt) {
        this.staffId = staffId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public boolean isUsable(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(OffsetDateTime at) {
        if (this.revokedAt == null) {
            this.revokedAt = at;
        }
    }
}
