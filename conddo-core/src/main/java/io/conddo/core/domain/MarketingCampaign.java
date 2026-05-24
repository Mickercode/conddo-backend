package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An email/SMS marketing campaign (§11.8). Tenant-scoped via RLS. Delivery stats
 * (sent/delivered/opened/clicked) are stored; actual bulk send is deferred until
 * the Notifications bulk-send integration, so a new campaign starts as a draft
 * with zero stats.
 */
@Entity
@Table(name = "marketing_campaigns")
public class MarketingCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status = "draft";

    private String content;

    @Column(name = "audience_size", nullable = false)
    private int audienceSize = 0;

    @Column(nullable = false)
    private int sent = 0;

    @Column(nullable = false)
    private int delivered = 0;

    @Column(nullable = false)
    private int opened = 0;

    @Column(nullable = false)
    private int clicked = 0;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected MarketingCampaign() {
    }

    public MarketingCampaign(UUID tenantId, String name, String type, String content,
                             int audienceSize, OffsetDateTime scheduledAt) {
        this.tenantId = tenantId;
        this.name = name;
        this.type = type;
        this.content = content;
        this.audienceSize = Math.max(0, audienceSize);
        this.scheduledAt = scheduledAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getContent() {
        return content;
    }

    public int getAudienceSize() {
        return audienceSize;
    }

    public int getSent() {
        return sent;
    }

    public int getDelivered() {
        return delivered;
    }

    public int getOpened() {
        return opened;
    }

    public int getClicked() {
        return clicked;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    /** Open rate as a percentage of sent (0 when nothing sent). */
    public double openRate() {
        return sent == 0 ? 0.0 : round1(opened * 100.0 / sent);
    }

    /** Click rate as a percentage of sent (0 when nothing sent). */
    public double clickRate() {
        return sent == 0 ? 0.0 : round1(clicked * 100.0 / sent);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
