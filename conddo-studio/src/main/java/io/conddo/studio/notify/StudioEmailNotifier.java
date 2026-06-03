package io.conddo.studio.notify;

import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import io.conddo.studio.sse.JobLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Mirrors high-priority {@link JobLifecycleEvent}s to email so staff don't miss
 * production-critical updates when the Studio tab is closed. Listens with
 * {@link TransactionPhase#AFTER_COMMIT} so emails never go out for a transition
 * that was later rolled back.
 *
 * <p>Limited to the three events where the in-app notification + SSE alone
 * aren't enough:
 * <ul>
 *   <li>{@link JobLifecycleEvent.JobReassigned} — a new owner needs to know.</li>
 *   <li>{@link JobLifecycleEvent.JobRevisionRequested} — work was bounced back.</li>
 *   <li>{@link JobLifecycleEvent.JobEscalated} — pages TEAM_LEAD + ADMIN.</li>
 * </ul>
 * Routine creates/claims/starts/submits/approves stay SSE-only to avoid noise.
 * Repository reads use {@code REQUIRES_NEW} since the originating transaction
 * has already committed.
 */
@Component
public class StudioEmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(StudioEmailNotifier.class);

    private final StaffRepository staffRepository;
    private final StudioMailer mailer;

    public StudioEmailNotifier(StaffRepository staffRepository, StudioMailer mailer) {
        this.staffRepository = staffRepository;
        this.mailer = mailer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReassigned(JobLifecycleEvent.JobReassigned event) {
        staffRepository.findById(event.newStaffId()).ifPresent(staff ->
                mailer.send(staff.getEmail(),
                        "New job assigned: " + event.jobNumber(),
                        htmlAssignment(staff, event.jobNumber()),
                        "Hi " + firstName(staff) + ",\n\nYou were assigned " + event.jobNumber()
                                + " in Conddo Studio. Open it from your queue to start work.\n\n— Conddo Studio"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onRevisionRequested(JobLifecycleEvent.JobRevisionRequested event) {
        staffRepository.findById(event.assignedTo()).ifPresent(staff -> {
            String feedback = event.feedback() == null ? "" : event.feedback();
            mailer.send(staff.getEmail(),
                    "Revision requested: " + event.jobNumber(),
                    htmlRevision(staff, event.jobNumber(), feedback),
                    "Hi " + firstName(staff) + ",\n\nQA returned " + event.jobNumber()
                            + " for revision.\n\nFeedback:\n" + feedback
                            + "\n\nOpen the job in Conddo Studio to apply changes and resubmit.\n\n— Conddo Studio");
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onEscalated(JobLifecycleEvent.JobEscalated event) {
        List<Staff> leads = staffRepository.findByRoleAndActiveTrue("TEAM_LEAD");
        List<Staff> admins = staffRepository.findByRoleAndActiveTrue("ADMIN");
        if (leads.isEmpty() && admins.isEmpty()) {
            log.warn("Escalation for {} has no TEAM_LEAD/ADMIN recipients", event.jobNumber());
            return;
        }
        String reason = event.reason() == null ? "(no reason given)" : event.reason();
        String text = "Job " + event.jobNumber() + " was escalated.\n\nReason:\n" + reason
                + "\n\nReview it in Conddo Studio.\n\n— Conddo Studio";
        String html = htmlEscalation(event.jobNumber(), reason);
        for (Staff recipient : concat(leads, admins)) {
            mailer.send(recipient.getEmail(),
                    "Escalation: " + event.jobNumber(), html, text);
        }
    }

    // ----- templates ---------------------------------------------------------

    private String htmlAssignment(Staff staff, String jobNumber) {
        return "<p>Hi " + escape(firstName(staff)) + ",</p>"
                + "<p>You were assigned <strong>" + escape(jobNumber) + "</strong> in Conddo Studio.</p>"
                + "<p>Open it from your queue to start work.</p>"
                + "<p>— Conddo Studio</p>";
    }

    private String htmlRevision(Staff staff, String jobNumber, String feedback) {
        return "<p>Hi " + escape(firstName(staff)) + ",</p>"
                + "<p>QA returned <strong>" + escape(jobNumber) + "</strong> for revision.</p>"
                + "<p><strong>Feedback</strong></p>"
                + "<blockquote style=\"border-left:3px solid #ccc;padding-left:12px;color:#444\">"
                + escape(feedback).replace("\n", "<br>") + "</blockquote>"
                + "<p>Open the job in Conddo Studio to apply changes and resubmit.</p>"
                + "<p>— Conddo Studio</p>";
    }

    private String htmlEscalation(String jobNumber, String reason) {
        return "<p>Job <strong>" + escape(jobNumber) + "</strong> was escalated.</p>"
                + "<p><strong>Reason</strong></p>"
                + "<blockquote style=\"border-left:3px solid #b00;padding-left:12px;color:#444\">"
                + escape(reason).replace("\n", "<br>") + "</blockquote>"
                + "<p>Review it in Conddo Studio.</p>"
                + "<p>— Conddo Studio</p>";
    }

    private static String firstName(Staff staff) {
        String full = staff.getFullName();
        if (full == null || full.isBlank()) {
            return "there";
        }
        int sp = full.indexOf(' ');
        return sp > 0 ? full.substring(0, sp) : full;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
