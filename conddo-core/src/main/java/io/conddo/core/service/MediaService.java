package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.MediaAsset;
import io.conddo.core.repository.MediaAssetRepository;
import io.conddo.core.storage.ObjectStorage;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tenant media library (§11.12): upload/list/get/delete over {@link ObjectStorage}
 * plus a tenant-scoped index ({@link MediaAsset}). Every method binds the tenant
 * first, so RLS scopes the index — uploads land under the tenant, and one tenant
 * can never list or delete another's files. Bytes live in the bucket; the API
 * returns short-lived presigned URLs for display (the bucket stays private).
 */
@Service
public class MediaService {

    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;   // 10 MB

    private final MediaAssetRepository repository;
    private final ObjectStorage storage;
    private final TenantSession tenantSession;
    private final String publicBaseUrl;
    private final Duration urlTtl;
    private final long maxBytes;

    public MediaService(MediaAssetRepository repository, ObjectStorage storage, TenantSession tenantSession,
                        @Value("${conddo.media.public-base-url:}") String publicBaseUrl,
                        @Value("${conddo.media.url-ttl:PT24H}") Duration urlTtl,
                        @Value("${conddo.media.max-bytes:" + DEFAULT_MAX_BYTES + "}") long maxBytes) {
        this.repository = repository;
        this.storage = storage;
        this.tenantSession = tenantSession;
        // The public base for stable URLs (public bucket / CDN). Trailing slash trimmed.
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.urlTtl = urlTtl;
        this.maxBytes = maxBytes;
    }

    /** Stores an uploaded file under the current tenant and indexes it. */
    @Transactional
    public MediaView upload(String originalName, String contentType, long size, InputStream data, String kind) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        validate(contentType, size);

        String key = keyFor(tenantId, originalName);
        storage.put(key, contentType, size, data);
        MediaAsset asset = repository.save(
                new MediaAsset(tenantId, key, contentType, size, originalName, normalizeKind(kind)));
        return view(asset);
    }

    @Transactional(readOnly = true)
    public Page<MediaView> list(String kind, Pageable pageable) {
        tenantSession.bind();
        Page<MediaAsset> page = (kind == null || kind.isBlank())
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByKindOrderByCreatedAtDesc(kind.trim().toLowerCase(), pageable);
        return page.map(this::view);
    }

    @Transactional(readOnly = true)
    public MediaView get(UUID id) {
        tenantSession.bind();
        return view(require(id));
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        MediaAsset asset = require(id);
        storage.delete(asset.getStorageKey());
        repository.delete(asset);
    }

    // ----- internals ----------------------------------------------------------

    private MediaView view(MediaAsset a) {
        return new MediaView(a.getId(), urlFor(a.getStorageKey()),
                a.getContentType(), a.getSizeBytes(), a.getOriginalName(), a.getKind(), a.getCreatedAt());
    }

    /**
     * A stable, publicly-fetchable URL when a public base / CDN is configured —
     * required because the URL is embedded in the public website and in emails
     * (where a short-lived signed URL would expire). Falls back to a presigned URL
     * for local/dev when no public base is set.
     */
    private String urlFor(String key) {
        return publicBaseUrl.isEmpty()
                ? storage.presignedGetUrl(key, urlTtl)
                : publicBaseUrl + "/" + key;
    }

    private MediaAsset require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Media not found"));
    }

    private void validate(String contentType, long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("File is empty");
        }
        if (size > maxBytes) {
            throw new IllegalArgumentException("File exceeds the " + (maxBytes / (1024 * 1024)) + "MB limit");
        }
        if (contentType == null || !(contentType.startsWith("image/") || contentType.equals("application/pdf"))) {
            throw new IllegalArgumentException("Unsupported file type: " + contentType
                    + " (allowed: images, PDF)");
        }
    }

    /** Per-tenant, collision-free object key with a sanitised original name. */
    private static String keyFor(UUID tenantId, String originalName) {
        String safe = (originalName == null || originalName.isBlank() ? "file" : originalName)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return "tenants/" + tenantId + "/" + UUID.randomUUID() + "-" + safe;
    }

    private static String normalizeKind(String kind) {
        return kind == null || kind.isBlank() ? "other" : kind.trim().toLowerCase();
    }

    /** A media asset plus a publicly-fetchable URL for display ({@code size} in bytes). */
    public record MediaView(UUID id, String url, String contentType, long size,
                            String originalName, String kind, OffsetDateTime createdAt) {
    }
}
