package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A scheduled social media post (§11.8). Tenant-scoped via RLS. {@code platforms}
 * holds the target channels (instagram/facebook/x/linkedin); actual publishing is
 * deferred (needs the social OAuth integration) — "publish" just flips the status.
 */
@Entity
@Table(name = "marketing_posts")
public class MarketingPost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String title;

    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platforms")
    private List<String> platforms = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_ids")
    private List<String> mediaIds = new ArrayList<>();

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(nullable = false)
    private String status = "scheduled";

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected MarketingPost() {
    }

    public MarketingPost(UUID tenantId, String title, String content,
                         List<String> platforms, List<String> mediaIds, OffsetDateTime scheduledAt) {
        this.tenantId = tenantId;
        this.title = title;
        this.content = content;
        if (platforms != null) {
            this.platforms = platforms;
        }
        if (mediaIds != null) {
            this.mediaIds = mediaIds;
        }
        this.scheduledAt = scheduledAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public List<String> getPlatforms() {
        return platforms;
    }

    public List<String> getMediaIds() {
        return mediaIds;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    /** The primary platform shown on a calendar chip (first target), or null. */
    public String primaryPlatform() {
        return platforms == null || platforms.isEmpty() ? null : platforms.get(0);
    }

    // ----- mutators -----------------------------------------------------------

    public void setTitle(String title) {
        this.title = title;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setPlatforms(List<String> platforms) {
        if (platforms != null) {
            this.platforms = platforms;
        }
    }

    public void setMediaIds(List<String> mediaIds) {
        if (mediaIds != null) {
            this.mediaIds = mediaIds;
        }
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        if (scheduledAt != null) {
            this.scheduledAt = scheduledAt;
        }
    }

    /** Marks the post published now (real channel delivery is deferred). */
    public void publish(OffsetDateTime at) {
        this.status = "published";
        this.publishedAt = at;
    }
}
