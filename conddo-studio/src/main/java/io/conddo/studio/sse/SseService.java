package io.conddo.studio.sse;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Studio Server-Sent Events hub (Infrastructure §13.4). Staff connect to
 * {@code GET /api/jobs/events}; this service holds their {@link SseEmitter}s in
 * memory keyed by staff id (one staff member can have multiple connections —
 * tabs, devices) and pushes job-board updates as Spring events fire.
 *
 * <p>Broadcasts subscribe to {@link JobLifecycleEvent} via
 * {@link TransactionalEventListener} with {@link TransactionPhase#AFTER_COMMIT}
 * so a client receiving an event and refetching detail sees the post-commit
 * state, never a half-applied transaction.
 *
 * <p>Filtering is in-memory (role / skills are snapshotted on subscribe), so the
 * board can scope updates: {@code job.created} reaches only staff whose skills
 * include the job's type; {@code job.submitted} reaches QA_REVIEWER; {@code
 * job.escalated} reaches TEAM_LEAD and ADMIN. A 30 s heartbeat keeps proxies
 * from closing idle connections.
 */
@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);
    /** 30 minutes — long enough that mobile/Wi-Fi blips don't churn the map. */
    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L;

    private final StaffRepository staffRepository;
    private final Map<UUID, List<Connection>> connections = new ConcurrentHashMap<>();

    public SseService(StaffRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    // ----- subscribe / unsubscribe -------------------------------------------

    /**
     * Open a new SSE stream for a staff member. Snapshots the staff's role + skills
     * for filtering and sends a one-shot {@code hello} event so the client can
     * confirm the connection is live.
     */
    public SseEmitter subscribe(UUID staffId) {
        Staff staff = staffRepository.findById(staffId)
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Connection connection = new Connection(emitter, staff.getRole(),
                List.copyOf(staff.getSkills()));
        connections.computeIfAbsent(staffId, k -> new CopyOnWriteArrayList<>()).add(connection);

        Runnable cleanup = () -> remove(staffId, connection);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        safeSend(emitter, "hello", Map.of("staffId", staffId, "role", staff.getRole(),
                "at", Instant.now().toString()));
        return emitter;
    }

    /** Used by tests / introspection. */
    public int connectionCount() {
        return connections.values().stream().mapToInt(List::size).sum();
    }

    public int connectionCount(UUID staffId) {
        List<Connection> list = connections.get(staffId);
        return list == null ? 0 : list.size();
    }

    // ----- broadcast plumbing -------------------------------------------------

    /** Direct push to one staff member's connections (no-op if not connected). */
    public void send(UUID staffId, String eventName, Object data) {
        List<Connection> list = connections.get(staffId);
        if (list == null) {
            return;
        }
        for (Connection conn : list) {
            push(staffId, conn, eventName, data);
        }
    }

    /** Push to every connected staff member whose snapshotted role matches. */
    public void broadcastToRole(String role, String eventName, Object data) {
        connections.forEach((staffId, list) -> {
            for (Connection conn : list) {
                if (role.equals(conn.role())) {
                    push(staffId, conn, eventName, data);
                }
            }
        });
    }

    /** Push to staff whose skills include the given skill (empty skills = all-trades). */
    public void broadcastToSkill(String skill, String eventName, Object data) {
        connections.forEach((staffId, list) -> {
            for (Connection conn : list) {
                if (conn.skills().isEmpty() || conn.skills().contains(skill)) {
                    push(staffId, conn, eventName, data);
                }
            }
        });
    }

    // ----- lifecycle listeners (AFTER_COMMIT) --------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobCreated(JobLifecycleEvent.JobCreated event) {
        // Anyone whose skills cover this job type sees it appear in their available queue.
        broadcastToSkill(event.jobTypeId(), "job.created", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobClaimed(JobLifecycleEvent.JobClaimed event) {
        // Everyone with that skill drops it from "available"; the claimer's queue picks it up.
        broadcastToSkill(event.jobTypeId(), "job.claimed", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobStarted(JobLifecycleEvent.JobStarted event) {
        send(event.staffId(), "job.started", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobSubmitted(JobLifecycleEvent.JobSubmitted event) {
        // QA dashboards refresh; the producer also sees their own status flip.
        broadcastToRole("QA_REVIEWER", "job.submitted", event);
        send(event.staffId(), "job.submitted", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobApproved(JobLifecycleEvent.JobApproved event) {
        send(event.assignedTo(), "job.approved", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobRevision(JobLifecycleEvent.JobRevisionRequested event) {
        send(event.assignedTo(), "job.revision_requested", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobReassigned(JobLifecycleEvent.JobReassigned event) {
        send(event.newStaffId(), "job.reassigned", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobEscalated(JobLifecycleEvent.JobEscalated event) {
        broadcastToRole("TEAM_LEAD", "job.escalated", event);
        broadcastToRole("ADMIN", "job.escalated", event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlaExtended(JobLifecycleEvent.JobSlaExtended event) {
        if (event.assignedTo() != null) {
            send(event.assignedTo(), "job.sla_extended", event);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotification(JobLifecycleEvent.NotificationCreated event) {
        send(event.staffId(), "notification.created", event);
    }

    // ----- heartbeat ---------------------------------------------------------

    /** Keeps idle connections alive through reverse proxies and load balancers. */
    @Scheduled(fixedRate = 30_000L)
    public void heartbeat() {
        if (connections.isEmpty()) {
            return;
        }
        String ts = Instant.now().toString();
        connections.forEach((staffId, list) -> {
            for (Connection conn : list) {
                push(staffId, conn, "heartbeat", Map.of("at", ts));
            }
        });
    }

    // ----- internals ---------------------------------------------------------

    private void push(UUID staffId, Connection conn, String eventName, Object data) {
        try {
            conn.emitter().send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            // Client gone (closed tab, network drop) — drop the emitter quietly.
            log.debug("Dropping SSE connection for staff {} on {}: {}", staffId, eventName, e.getMessage());
            remove(staffId, conn);
        }
    }

    private void safeSend(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException ignore) {
            // First-send failures are reported via onError → cleanup runs there.
        }
    }

    private void remove(UUID staffId, Connection conn) {
        List<Connection> list = connections.get(staffId);
        if (list == null) {
            return;
        }
        list.remove(conn);
        if (list.isEmpty()) {
            connections.remove(staffId, list);
        }
    }

    /** One live SSE stream + the role/skills snapshot used to filter broadcasts. */
    private record Connection(SseEmitter emitter, String role, List<String> skills) {
    }
}
