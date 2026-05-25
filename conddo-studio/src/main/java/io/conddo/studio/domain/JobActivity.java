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

/** An entry in a job's activity timeline (claims, transitions, escalations). */
@Entity
@Table(name = "job_activity")
public class JobActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "staff_id")
    private UUID staffId;

    @Column(nullable = false)
    private String action;

    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected JobActivity() {
    }

    public JobActivity(UUID jobId, UUID staffId, String action, String detail) {
        this.jobId = jobId;
        this.staffId = staffId;
        this.action = action;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public String getAction() {
        return action;
    }

    public String getDetail() {
        return detail;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
