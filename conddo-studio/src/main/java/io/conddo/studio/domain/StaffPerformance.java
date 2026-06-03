package io.conddo.studio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One staff member's performance snapshot for a calendar month — {@code (staff_id,
 * period_month)} is unique. Recomputed daily by {@link io.conddo.studio.performance.PerformanceService};
 * historical months are immutable in practice (recalc only touches the current
 * month). Lives in the {@code jobs} schema because it's reporting data, not a
 * source-of-truth aggregate.
 */
@Entity
@Table(schema = "jobs", name = "staff_performance",
        uniqueConstraints = @UniqueConstraint(name = "uk_staff_perf_month",
                columnNames = {"staff_id", "period_month"}))
public class StaffPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    /** First day of the month this row covers (e.g. 2026-06-01 for June 2026). */
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "jobs_completed", nullable = false)
    private int jobsCompleted;

    @Column(name = "jobs_target", nullable = false)
    private int jobsTarget = 15;

    @Column(name = "first_pass_qa_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal firstPassQaRate = BigDecimal.ZERO;

    @Column(name = "avg_build_minutes", nullable = false)
    private int avgBuildMinutes;

    @Column(name = "sla_compliance_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal slaComplianceRate = new BigDecimal("100.00");

    @Column(name = "revision_count", nullable = false)
    private int revisionCount;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected StaffPerformance() {
    }

    public StaffPerformance(UUID staffId, LocalDate periodMonth) {
        this.staffId = staffId;
        this.periodMonth = periodMonth;
    }

    public void apply(int jobsCompleted, int jobsTarget, BigDecimal firstPassQaRate,
                      int avgBuildMinutes, BigDecimal slaComplianceRate, int revisionCount) {
        this.jobsCompleted = jobsCompleted;
        this.jobsTarget = jobsTarget;
        this.firstPassQaRate = firstPassQaRate;
        this.avgBuildMinutes = avgBuildMinutes;
        this.slaComplianceRate = slaComplianceRate;
        this.revisionCount = revisionCount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public int getJobsCompleted() {
        return jobsCompleted;
    }

    public int getJobsTarget() {
        return jobsTarget;
    }

    public BigDecimal getFirstPassQaRate() {
        return firstPassQaRate;
    }

    public int getAvgBuildMinutes() {
        return avgBuildMinutes;
    }

    public BigDecimal getSlaComplianceRate() {
        return slaComplianceRate;
    }

    public int getRevisionCount() {
        return revisionCount;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
