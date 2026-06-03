package io.conddo.studio.jobs;

import io.conddo.studio.ai.AiAssistantService;
import io.conddo.studio.common.ConflictException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.config.StudioProperties;
import io.conddo.studio.domain.Job;
import io.conddo.studio.domain.JobActivity;
import io.conddo.studio.domain.JobType;
import io.conddo.studio.domain.QaReview;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.domain.StaffNotification;
import io.conddo.studio.repository.JobActivityRepository;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.repository.JobTypeRepository;
import io.conddo.studio.repository.QaReviewRepository;
import io.conddo.studio.repository.StaffNotificationRepository;
import io.conddo.studio.repository.StaffRepository;
import io.conddo.studio.sse.JobLifecycleEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Studio job pipeline (Infrastructure §12/§13): create, claim, start, submit,
 * QA review, and admin operations over the {@link Job} state machine. Internal
 * staff only (no tenant RLS). SLA tone (GREEN/AMBER/RED) is derived at read time.
 *
 * <p>Each transition publishes a {@link JobLifecycleEvent} so {@code SseService}
 * (and any future listeners) can react after the DB commit lands — see §13.4.
 * Scheduled SLA escalation is deferred to {@code SlaMonitorService}.
 */
@Service
public class JobService {

    private static final Map<String, String> NUMBER_PREFIX = Map.of(
            "WEBSITE_BUILD", "WB", "WEBSITE_REVISION", "WR", "GRAPHIC_DESIGN", "GD",
            "AD_CREATIVE", "AD", "BRAND_KIT", "BK", "CONTENT_WRITING", "CW");

    private static final List<String> MY_OPEN_STATUSES =
            List.of("ASSIGNED", "IN_PROGRESS", "REVISION", "SUBMITTED");
    private static final List<String> CLAIMABLE_STATUSES = List.of("QUEUED", "AVAILABLE");

    private final JobRepository jobRepository;
    private final JobTypeRepository jobTypeRepository;
    private final JobActivityRepository activityRepository;
    private final QaReviewRepository qaReviewRepository;
    private final StaffRepository staffRepository;
    private final StaffNotificationRepository notifications;
    private final JdbcTemplate jdbcTemplate;
    private final StudioProperties properties;
    private final AiAssistantService aiAssistant;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public JobService(JobRepository jobRepository, JobTypeRepository jobTypeRepository,
                      JobActivityRepository activityRepository, QaReviewRepository qaReviewRepository,
                      StaffRepository staffRepository, StaffNotificationRepository notifications,
                      JdbcTemplate jdbcTemplate, StudioProperties properties,
                      AiAssistantService aiAssistant, ApplicationEventPublisher events) {
        this.jobRepository = jobRepository;
        this.jobTypeRepository = jobTypeRepository;
        this.activityRepository = activityRepository;
        this.qaReviewRepository = qaReviewRepository;
        this.staffRepository = staffRepository;
        this.notifications = notifications;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.aiAssistant = aiAssistant;
        this.events = events;
    }

    // ----- creation (admin / future auto-create on signup) --------------------

    @Transactional
    public Job create(String jobTypeId, UUID tenantId, String title, Map<String, Object> brief) {
        JobType type = jobTypeRepository.findById(jobTypeId)
                .orElseThrow(() -> new NotFoundException("Unknown job type: " + jobTypeId));
        OffsetDateTime now = OffsetDateTime.now(clock);
        Job job = new Job(nextJobNumber(jobTypeId), jobTypeId, tenantId, title, brief,
                "AVAILABLE", now.plusHours(type.getSlaHours()));
        job = jobRepository.save(job);
        log(job.getId(), null, "JOB_CREATED", "Job created");
        events.publishEvent(new JobLifecycleEvent.JobCreated(job.getId(), job.getJobNumber(),
                job.getJobTypeId(), job.getStatus(), slaTone(job)));
        return job;
    }

    // ----- staff queues -------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JobView> myJobs(UUID staffId) {
        return view(jobRepository.findByAssignedToAndStatusInOrderBySlaDeadlineAsc(staffId, MY_OPEN_STATUSES));
    }

    /** Available jobs whose type targets one of the staff member's skills. */
    @Transactional(readOnly = true)
    public List<JobView> available(UUID staffId) {
        Staff staff = requireStaff(staffId);
        return jobRepository.findByStatusInOrderBySlaDeadlineAsc(CLAIMABLE_STATUSES).stream()
                .filter(job -> staff.getSkills().isEmpty() || staff.getSkills().contains(job.getJobTypeId()))
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public JobDetail detail(UUID id) {
        Job job = require(id);
        String assignedName = job.getAssignedTo() == null ? null
                : staffRepository.findById(job.getAssignedTo()).map(Staff::getFullName).orElse(null);
        return new JobDetail(job, slaTone(job), assignedName,
                activityRepository.findByJobIdOrderByCreatedAtDesc(id),
                qaReviewRepository.findByJobIdOrderByCreatedAtDesc(id));
    }

    // ----- staff lifecycle ----------------------------------------------------

    @Transactional
    public Job claim(UUID jobId, UUID staffId) {
        Job job = require(jobId);
        if (!CLAIMABLE_STATUSES.contains(job.getStatus())) {
            throw new ConflictException("Job " + job.getJobNumber() + " is not available to claim");
        }
        job.claim(staffId, OffsetDateTime.now(clock));
        log(jobId, staffId, "JOB_CLAIMED", null);
        Job saved = jobRepository.save(job);
        events.publishEvent(new JobLifecycleEvent.JobClaimed(saved.getId(), saved.getJobNumber(),
                saved.getJobTypeId(), staffId));
        return saved;
    }

    @Transactional
    public Job start(UUID jobId, UUID staffId) {
        Job job = requireAssigned(jobId, staffId);
        if (!List.of("ASSIGNED", "REVISION").contains(job.getStatus())) {
            throw new ConflictException("Job " + job.getJobNumber() + " cannot be started from " + job.getStatus());
        }
        job.start(OffsetDateTime.now(clock));
        log(jobId, staffId, "JOB_STARTED", null);
        Job saved = jobRepository.save(job);
        events.publishEvent(new JobLifecycleEvent.JobStarted(saved.getId(), saved.getJobNumber(), staffId));
        return saved;
    }

    @Transactional
    public Job submit(UUID jobId, UUID staffId, String studioUrl, String notes) {
        Job job = requireAssigned(jobId, staffId);
        if (!"IN_PROGRESS".equals(job.getStatus())) {
            throw new ConflictException("Job " + job.getJobNumber() + " must be in progress to submit");
        }
        job.submit(OffsetDateTime.now(clock), studioUrl);
        log(jobId, staffId, "JOB_SUBMITTED", notes);
        Job saved = jobRepository.save(job);
        events.publishEvent(new JobLifecycleEvent.JobSubmitted(saved.getId(), saved.getJobNumber(),
                saved.getJobTypeId(), staffId));
        return saved;
    }

    // ----- QA -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JobView> qaQueue() {
        return view(jobRepository.findByStatusInOrderBySlaDeadlineAsc(List.of("SUBMITTED", "IN_REVIEW")));
    }

    @Transactional
    public Job qaStart(UUID jobId, UUID reviewerId) {
        Job job = require(jobId);
        if (!"SUBMITTED".equals(job.getStatus())) {
            throw new ConflictException("Only submitted jobs can be reviewed");
        }
        job.beginReview();
        log(jobId, reviewerId, "QA_STARTED", null);
        return jobRepository.save(job);
    }

    @Transactional
    public Job qaApprove(UUID jobId, UUID reviewerId, Map<String, Object> checklist, String positiveNotes) {
        Job job = requireReviewable(jobId);
        qaReviewRepository.save(new QaReview(jobId, reviewerId, "APPROVED", checklist, null, positiveNotes));
        job.approve(OffsetDateTime.now(clock));
        log(jobId, reviewerId, "QA_APPROVED", null);
        Job saved = jobRepository.save(job);
        if (saved.getAssignedTo() != null) {
            events.publishEvent(new JobLifecycleEvent.JobApproved(saved.getId(), saved.getJobNumber(),
                    saved.getAssignedTo()));
        }
        return saved;
    }

    @Transactional
    public Job qaReturn(UUID jobId, UUID reviewerId, Map<String, Object> checklist, String feedback) {
        Job job = requireReviewable(jobId);
        qaReviewRepository.save(new QaReview(jobId, reviewerId, "REVISION", checklist, feedback, null));
        job.returnForRevision();
        log(jobId, reviewerId, "QA_RETURNED", feedback);
        notifyStaff(job.getAssignedTo(), "QA_REVISION", "Revision requested",
                job.getJobNumber() + " was returned for revision", jobId, job.getJobNumber());
        Job saved = jobRepository.save(job);
        if (saved.getAssignedTo() != null) {
            events.publishEvent(new JobLifecycleEvent.JobRevisionRequested(saved.getId(), saved.getJobNumber(),
                    saved.getAssignedTo(), feedback));
        }
        return saved;
    }

    // ----- admin --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JobView> allJobs() {
        return view(jobRepository.findAllByOrderBySlaDeadlineAsc());
    }

    @Transactional
    public Job reassign(UUID jobId, UUID newStaffId) {
        Job job = require(jobId);
        requireStaff(newStaffId);
        job.reassign(newStaffId, OffsetDateTime.now(clock));
        log(jobId, newStaffId, "JOB_REASSIGNED", null);
        notifyStaff(newStaffId, "JOB_ASSIGNED", "New job assigned",
                "You were assigned " + job.getJobNumber(), jobId, job.getJobNumber());
        Job saved = jobRepository.save(job);
        events.publishEvent(new JobLifecycleEvent.JobReassigned(saved.getId(), saved.getJobNumber(), newStaffId));
        return saved;
    }

    @Transactional
    public Job extendSla(UUID jobId, int hours, String reason) {
        if (hours <= 0) {
            throw new IllegalArgumentException("Extension hours must be positive");
        }
        Job job = require(jobId);
        job.extendSla(hours);
        log(jobId, null, "SLA_EXTENDED", "+" + hours + "h: " + (reason == null ? "" : reason));
        Job saved = jobRepository.save(job);
        events.publishEvent(new JobLifecycleEvent.JobSlaExtended(saved.getId(), saved.getJobNumber(),
                hours, saved.getAssignedTo()));
        return saved;
    }

    @Transactional
    public Job escalate(UUID jobId, String reason) {
        Job job = require(jobId);
        job.escalate();
        log(jobId, null, "JOB_ESCALATED", reason);
        Job saved = jobRepository.save(job);
        events.publishEvent(new JobLifecycleEvent.JobEscalated(saved.getId(), saved.getJobNumber(), reason));
        return saved;
    }

    // ----- AI assistant (§8) --------------------------------------------------

    /** Generates AI copy for a section and stores it on the job (appears in the detail). */
    @Transactional
    public AiAssistantService.CopyResult aiSuggest(UUID jobId, String section) {
        Job job = require(jobId);
        AiAssistantService.CopyResult result = aiAssistant.generateSectionCopy(job.getBrief(), section);
        if (result.available()) {
            job.putAiSuggestion(result.section(), result.copy());
            jobRepository.save(job);
            log(jobId, null, "AI_SUGGEST", "AI copy generated for " + result.section());
        }
        return result;
    }

    /** Generates an accessible colour palette from a primary hex (no job needed). */
    public AiAssistantService.PaletteResult aiPalette(String primaryHex) {
        return aiAssistant.generatePalette(primaryHex);
    }

    /** Palette grounded in the vertical's Design Standard Library entries. */
    public AiAssistantService.PaletteResult aiPalette(String primaryHex, String vertical) {
        return aiAssistant.generatePalette(primaryHex, vertical);
    }

    /** Runs an AI QA scan over a job's brief + submission (read-only). */
    @Transactional(readOnly = true)
    public AiAssistantService.QaScanResult aiScan(UUID jobId) {
        return aiAssistant.scanSubmission(require(jobId));
    }

    /** Ranks candidate images for a section using the job's vertical. */
    @Transactional(readOnly = true)
    public AiAssistantService.RankResult aiRankImages(UUID jobId, List<String> imageUrls, String sectionType) {
        Job job = require(jobId);
        Object vertical = job.getBrief() == null ? null : job.getBrief().get("vertical");
        return aiAssistant.rankImages(imageUrls,
                vertical == null ? "general" : vertical.toString(),
                sectionType == null || sectionType.isBlank() ? "hero" : sectionType);
    }

    // ----- performance --------------------------------------------------------

    @Transactional(readOnly = true)
    public Performance performance(UUID staffId) {
        requireStaff(staffId);
        long completed = jobRepository.countByAssignedToAndStatusIn(staffId, List.of("APPROVED", "DELIVERED"));
        long returns = qaReviewRepository.countByReviewerIdAndOutcome(staffId, "REVISION");
        long approvals = qaReviewRepository.countByReviewerIdAndOutcome(staffId, "APPROVED");
        long reviewed = returns + approvals;
        double firstPass = reviewed == 0 ? 100.0 : round1(approvals * 100.0 / reviewed);
        return new Performance(staffId, completed, 15, firstPass, returns);
    }

    // ----- SLA / helpers ------------------------------------------------------

    /** GREEN/AMBER/RED from hours to the SLA deadline; overdue counts as RED. */
    public String slaTone(Job job) {
        long hours = ChronoUnit.HOURS.between(OffsetDateTime.now(clock), job.getSlaDeadline());
        if (hours <= properties.sla().redHours()) {
            return "RED";
        }
        return hours <= properties.sla().amberHours() ? "AMBER" : "GREEN";
    }

    private List<JobView> view(List<Job> jobs) {
        return jobs.stream().map(this::view).toList();
    }

    private JobView view(Job job) {
        return new JobView(job, slaTone(job));
    }

    private String nextJobNumber(String jobTypeId) {
        Long seq = jdbcTemplate.queryForObject("select nextval('studio.job_number_seq')", Long.class);
        return NUMBER_PREFIX.getOrDefault(jobTypeId, "JOB") + "-" + seq;
    }

    private void log(UUID jobId, UUID staffId, String action, String detail) {
        activityRepository.save(new JobActivity(jobId, staffId, action, detail));
    }

    private void notifyStaff(UUID staffId, String type, String title, String message, UUID jobId, String jobNumber) {
        if (staffId == null) {
            return;
        }
        StaffNotification saved = notifications.save(new StaffNotification(staffId, type, title, message, jobId));
        events.publishEvent(new JobLifecycleEvent.NotificationCreated(jobId, jobNumber, staffId,
                saved.getId(), type, title, message));
    }

    private Job require(UUID id) {
        return jobRepository.findById(id).orElseThrow(() -> new NotFoundException("Job not found"));
    }

    private Job requireAssigned(UUID jobId, UUID staffId) {
        Job job = require(jobId);
        if (!staffId.equals(job.getAssignedTo())) {
            throw new ConflictException("Job " + job.getJobNumber() + " is not assigned to you");
        }
        return job;
    }

    private Job requireReviewable(UUID jobId) {
        Job job = require(jobId);
        if (!List.of("SUBMITTED", "IN_REVIEW").contains(job.getStatus())) {
            throw new ConflictException("Job " + job.getJobNumber() + " is not awaiting review");
        }
        return job;
    }

    private Staff requireStaff(UUID id) {
        return staffRepository.findById(id).orElseThrow(() -> new NotFoundException("Staff member not found"));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** A job plus its derived SLA tone, for board cards/lists. */
    public record JobView(Job job, String slaTone) {
    }

    /** Full job detail: the job, tone, assignee name, activity, and QA reviews. */
    public record JobDetail(Job job, String slaTone, String assignedToName,
                            List<JobActivity> activity, List<QaReview> qaReviews) {
    }

    /** A staff member's performance snapshot (computed live). */
    public record Performance(UUID staffId, long jobsCompleted, int jobsTarget,
                              double firstPassQaRate, long revisionsReceived) {
    }
}
