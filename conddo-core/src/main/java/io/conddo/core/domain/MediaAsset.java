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
 * A tenant's uploaded file (§11.12). The bytes live in object storage; this row
 * is the index — the storage key plus metadata. Tenant-scoped via RLS.
 */
@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /** The public, CDN-backed URL (Cloudinary secure_url) — stable, embedded everywhere. */
    @Column(name = "url")
    private String url;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "original_name")
    private String originalName;

    private String kind;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected MediaAsset() {
    }

    public MediaAsset(UUID tenantId, String storageKey, String url, String contentType, long sizeBytes,
                      String originalName, String kind) {
        this.tenantId = tenantId;
        this.storageKey = storageKey;
        this.url = url;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.originalName = originalName;
        this.kind = kind;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getUrl() {
        return url;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getKind() {
        return kind;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
