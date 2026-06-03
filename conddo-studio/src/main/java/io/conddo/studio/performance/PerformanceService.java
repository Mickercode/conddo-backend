package io.conddo.studio.performance;

import io.conddo.studio.domain.Staff;
import io.conddo.studio.domain.StaffPerformance;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.repository.QaReviewRepository;
import io.conddo.studio.repository.StaffPerformanceRepository;
import io.conddo.studio.repository.StaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Persistent monthly performance snapshots (Infrastructure §8). The live
 * snapshot exposed by {@link io.conddo.studio.jobs.JobService#performance(UUID)}
 * is fast enough for one staff member at a time, but the admin team board
 * wants every active staff member at once — and at scale that's a lot of
 * one-off counts. This service rolls the same aggregations into
 * {@code jobs.staff_performance}, refreshed daily.
 *
 * <p>Daily recalc only touches the current calendar month's row (upsert by
 * {@code (staff_id, period_month)}); past months are effectively immutable
 * once a new month starts. Computation matches today's
 * {@code JobService.performance(...)}: completed = APPROVED+DELIVERED, first-pass
 * = QA approvals / (approvals + revisions). The DB also tracks SLA-compliance
 * and avg-build-minutes — left at their defaults until a future slice surfaces
 * the deeper math.
 */
@Service
public class PerformanceService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceService.class);
    private static final int DEFAULT_JOBS_TARGET = 15;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final StaffRepository staffRepository;
    private final StaffPerformanceRepository perfRepository;
    private final JobRepository jobRepository;
    private final QaReviewRepository qaReviewRepository;
    private final Clock clock;

    @Autowired
    public PerformanceService(StaffRepository staffRepository, StaffPerformanceRepository perfRepository,
                              JobRepository jobRepository, QaReviewRepository qaReviewRepository) {
        this(staffRepository, perfRepository, jobRepository, qaReviewRepository, Clock.systemUTC());
    }

    PerformanceService(StaffRepository staffRepository, StaffPerformanceRepository perfRepository,
                       JobRepository jobRepository, QaReviewRepository qaReviewRepository, Clock clock) {
        this.staffRepository = staffRepository;
        this.perfRepository = perfRepository;
        this.jobRepository = jobRepository;
        this.qaReviewRepository = qaReviewRepository;
        this.clock = clock;
    }

    // ----- reads --------------------------------------------------------------

    /** All snapshots for the current month, highest completed first. */
    @Transactional(readOnly = true)
    public List<StaffPerformance> currentTeamBoard() {
        return perfRepository.findByPeriodMonthOrderByJobsCompletedDesc(currentMonth());
    }

    /** Up to 12 months of history for one staff member, newest first. */
    @Transactional(readOnly = true)
    public List<StaffPerformance> history(UUID staffId) {
        return perfRepository.findTop12ByStaffIdOrderByPeriodMonthDesc(staffId);
    }

    // ----- daily recalc -------------------------------------------------------

    /**
     * Every day at 02:00 UTC (configurable via {@code studio.performance.cron}),
     * upsert every active staff member's snapshot for the current month. Cron
     * is preferred over fixedRate so the job runs in a quiet window regardless
     * of when the service last restarted.
     */
    @Scheduled(cron = "${studio.performance.cron:0 0 2 * * *}", zone = "UTC")
    @Transactional
    public void dailyRecalc() {
        LocalDate month = currentMonth();
        List<Staff> staff = staffRepository.findAll();
        int touched = 0;
        for (Staff member : staff) {
            if (!member.isActive()) {
                continue;
            }
            recalc(member.getId(), month);
            touched++;
        }
        log.info("Recalculated performance for {} staff member(s), month={}", touched, month);
    }

    /** Compute + upsert one staff member's row for the given month. Public so admins can force a refresh. */
    @Transactional
    public StaffPerformance recalc(UUID staffId, LocalDate periodMonth) {
        int completed = (int) jobRepository.countByAssignedToAndStatusIn(staffId,
                List.of("APPROVED", "DELIVERED"));
        int revisions = (int) qaReviewRepository.countByReviewerIdAndOutcome(staffId, "REVISION");
        int approvals = (int) qaReviewRepository.countByReviewerIdAndOutcome(staffId, "APPROVED");
        int reviewed = revisions + approvals;
        BigDecimal firstPass = reviewed == 0
                ? HUNDRED
                : new BigDecimal(approvals).multiply(HUNDRED)
                        .divide(new BigDecimal(reviewed), 2, RoundingMode.HALF_UP);

        StaffPerformance row = perfRepository.findByStaffIdAndPeriodMonth(staffId, periodMonth)
                .orElseGet(() -> new StaffPerformance(staffId, periodMonth));
        // Avg build minutes + SLA compliance need start/end timestamps — left at
        // their defaults until §8 surfaces those queries. Documented in the issue.
        row.apply(completed, DEFAULT_JOBS_TARGET, firstPass,
                row.getAvgBuildMinutes(), row.getSlaComplianceRate(), revisions);
        return perfRepository.save(row);
    }

    private LocalDate currentMonth() {
        return LocalDate.now(clock).withDayOfMonth(1);
    }
}
