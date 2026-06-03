package io.conddo.studio.sla;

import io.conddo.studio.domain.Job;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.sse.SseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Periodic SLA enforcement (Infrastructure §7). Every {@code studio.sla.monitor-interval-ms}
 * the service walks every active job, derives its current GREEN/AMBER/RED tone,
 * pushes a snapshot of the non-green jobs to TEAM_LEAD + ADMIN over SSE so the
 * board can recolour cards, and auto-escalates anything past its deadline.
 *
 * <p>Auto-escalation calls {@link JobService#escalate(UUID, String)}, which is
 * already wired to publish {@code JobLifecycleEvent.JobEscalated} — so the
 * existing SSE broadcast (to leads + admins) and Brevo email mirror (Slice D.2)
 * both fire automatically. No new fan-out code in this slice.
 *
 * <p>Active = statuses where the SLA still ticks ({@code ASSIGNED}, {@code IN_PROGRESS},
 * {@code SUBMITTED}, {@code IN_REVIEW}, {@code REVISION}). {@code ESCALATED},
 * {@code APPROVED}, {@code DELIVERED}, {@code CANCELLED} are off-ramps — the
 * status filter prevents re-escalating an already-escalated job.
 */
@Service
public class SlaMonitorService {

    /** Statuses where the SLA still counts down. ESCALATED is excluded so we don't loop. */
    static final List<String> ACTIVE_STATUSES =
            List.of("ASSIGNED", "IN_PROGRESS", "SUBMITTED", "IN_REVIEW", "REVISION");

    private static final Logger log = LoggerFactory.getLogger(SlaMonitorService.class);

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final SseService sseService;
    private final Clock clock;

    @Autowired
    public SlaMonitorService(JobRepository jobRepository, JobService jobService, SseService sseService) {
        this(jobRepository, jobService, sseService, Clock.systemUTC());
    }

    SlaMonitorService(JobRepository jobRepository, JobService jobService, SseService sseService, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.sseService = sseService;
        this.clock = clock;
    }

    /**
     * Wakes every {@code studio.sla.monitor-interval-ms} (default 5 minutes). The
     * default is long enough that the database isn't hammered, short enough that
     * a RED job is caught within one tick of breaching its deadline.
     */
    @Scheduled(fixedDelayString = "${studio.sla.monitor-interval-ms:300000}",
            initialDelayString = "${studio.sla.monitor-initial-delay-ms:60000}")
    @Transactional
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Job> active = jobRepository.findByStatusInOrderBySlaDeadlineAsc(ACTIVE_STATUSES);

        List<SlaSnapshotItem> snapshot = new ArrayList<>();
        List<UUID> escalated = new ArrayList<>();

        for (Job job : active) {
            String tone = jobService.slaTone(job);
            if (!"GREEN".equals(tone)) {
                long hoursToDeadline = ChronoUnit.HOURS.between(now, job.getSlaDeadline());
                snapshot.add(new SlaSnapshotItem(job.getId(), job.getJobNumber(), tone,
                        hoursToDeadline, job.getAssignedTo()));
            }
            if (now.isAfter(job.getSlaDeadline())) {
                // The transactional event listener fires AFTER_COMMIT, so the existing
                // SseService + StudioEmailNotifier hooks page leads/admins for us.
                jobService.escalate(job.getId(), "Auto-escalated: SLA breached at " + now);
                escalated.add(job.getId());
            }
        }

        if (!snapshot.isEmpty()) {
            sseService.broadcastToRole("TEAM_LEAD", "sla.tick", snapshot);
            sseService.broadcastToRole("ADMIN", "sla.tick", snapshot);
        }
        if (!escalated.isEmpty()) {
            log.info("Auto-escalated {} job(s) past SLA", escalated.size());
        }
    }

    /**
     * Snapshot of one non-green job at tick time. Sent as an array so the frontend
     * applies one re-render per tick instead of one per job.
     */
    public record SlaSnapshotItem(UUID jobId, String jobNumber, String tone,
                                  long hoursToDeadline, UUID assignedTo) {
    }
}
