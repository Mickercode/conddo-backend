package io.conddo.studio.domain;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A Studio job moving through the production pipeline (Infrastructure §6.3/§12).
 * States: QUEUED → AVAILABLE → ASSIGNED → IN_PROGRESS → SUBMITTED → IN_REVIEW →
 * (APPROVED → DELIVERED | REVISION → …); ESCALATED / CANCELLED are off-ramps.
 * Lifecycle transitions live here; the service enforces who/when.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_number", nullable = false, unique = true)
    private String jobNumber;

    @Column(name = "job_type_id", nullable = false)
    private String jobTypeId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> brief = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> assets = List.of();

    @Column(nullable = false)
    private String status = "QUEUED";

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "sla_deadline", nullable = false)
    private OffsetDateTime slaDeadline;

    @Column(name = "sla_extended_by", nullable = false)
    private int slaExtendedBy = 0;

    @Column(name = "revision_count", nullable = false)
    private int revisionCount = 0;

    @Column(name = "studio_url")
    private String studioUrl;

    /** AI-generated copy suggestions, keyed by section (§8). Shown in the job detail. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_suggestions", nullable = false)
    private Map<String, Object> aiSuggestions = new java.util.HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Job() {
    }

    public Job(String jobNumber, String jobTypeId, UUID tenantId, String title,
               Map<String, Object> brief, String status, OffsetDateTime slaDeadline) {
        this.jobNumber = jobNumber;
        this.jobTypeId = jobTypeId;
        this.tenantId = tenantId;
        this.title = title;
        if (brief != null) {
            this.brief = brief;
        }
        if (status != null) {
            this.status = status;
        }
        this.slaDeadline = slaDeadline;
    }

    // ----- transitions --------------------------------------------------------

    public void claim(UUID staffId, OffsetDateTime at) {
        this.status = "ASSIGNED";
        this.assignedTo = staffId;
        this.assignedAt = at;
    }

    public void start(OffsetDateTime at) {
        this.status = "IN_PROGRESS";
        if (this.startedAt == null) {
            this.startedAt = at;
        }
    }

    public void submit(OffsetDateTime at, String studioUrl) {
        this.status = "SUBMITTED";
        this.submittedAt = at;
        if (studioUrl != null) {
            this.studioUrl = studioUrl;
        }
    }

    public void beginReview() {
        this.status = "IN_REVIEW";
    }

    public void approve(OffsetDateTime at) {
        this.status = "APPROVED";
        this.approvedAt = at;
    }

    public void returnForRevision() {
        this.status = "REVISION";
        this.revisionCount += 1;
    }

    public void markDelivered(OffsetDateTime at) {
        this.status = "DELIVERED";
        this.deliveredAt = at;
    }

    public void escalate() {
        this.status = "ESCALATED";
    }

    public void reassign(UUID staffId, OffsetDateTime at) {
        this.assignedTo = staffId;
        this.assignedAt = at;
        this.status = "ASSIGNED";
    }

    public void extendSla(int hours) {
        this.slaDeadline = this.slaDeadline.plusHours(hours);
        this.slaExtendedBy += hours;
    }

    // ----- getters ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getJobNumber() {
        return jobNumber;
    }

    public String getJobTypeId() {
        return jobTypeId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTitle() {
        return title;
    }

    public Map<String, Object> getBrief() {
        return brief;
    }

    public List<Map<String, Object>> getAssets() {
        return assets;
    }

    /** Replaces the full assets array. Used by {@code AssetService} on upload/delete. */
    public void setAssets(List<Map<String, Object>> assets) {
        this.assets = assets == null ? List.of() : assets;
    }

    public String getStatus() {
        return status;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public OffsetDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public OffsetDateTime getSlaDeadline() {
        return slaDeadline;
    }

    public int getSlaExtendedBy() {
        return slaExtendedBy;
    }

    public int getRevisionCount() {
        return revisionCount;
    }

    public String getStudioUrl() {
        return studioUrl;
    }

    public Map<String, Object> getAiSuggestions() {
        return aiSuggestions;
    }

    /** Records an AI copy suggestion for a section (§8), e.g. HERO → {headline, …}. */
    public void putAiSuggestion(String section, Object value) {
        if (this.aiSuggestions == null) {
            this.aiSuggestions = new java.util.HashMap<>();
        }
        this.aiSuggestions.put(section, value);
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
