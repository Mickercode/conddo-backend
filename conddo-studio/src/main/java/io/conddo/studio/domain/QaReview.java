package io.conddo.studio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** A QA review outcome for a submitted job (APPROVED or REVISION + checklist). */
@Entity
@Table(name = "qa_reviews")
public class QaReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Column(nullable = false)
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> checklist = Map.of();

    @Column(name = "reviewer_notes")
    private String reviewerNotes;

    @Column(name = "positive_notes")
    private String positiveNotes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected QaReview() {
    }

    public QaReview(UUID jobId, UUID reviewerId, String outcome, Map<String, Object> checklist,
                    String reviewerNotes, String positiveNotes) {
        this.jobId = jobId;
        this.reviewerId = reviewerId;
        this.outcome = outcome;
        if (checklist != null) {
            this.checklist = checklist;
        }
        this.reviewerNotes = reviewerNotes;
        this.positiveNotes = positiveNotes;
    }

    public UUID getId() {
        return id;
    }

    public String getOutcome() {
        return outcome;
    }

    public Map<String, Object> getChecklist() {
        return checklist;
    }

    public String getReviewerNotes() {
        return reviewerNotes;
    }

    public String getPositiveNotes() {
        return positiveNotes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
