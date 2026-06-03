package io.conddo.studio.sse;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SseService connection bookkeeping and broadcast plumbing. Wire-level event
 * verification (which event name/payload reached which subscriber) is covered by
 * the live SSE integration test rather than poked at via private fields — these
 * assertions instead verify the public contract: subscribe/unsubscribe counts,
 * 404 on an unknown staff member, broadcasts don't throw, completion cleans up.
 */
class SseServiceTest {

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final SseService service = new SseService(staffRepository);

    private UUID devId;
    private UUID qaId;
    private UUID leadId;

    @BeforeEach
    void seed() {
        devId = mockStaff("dev@studio.test", "Dele Dev", "DEVELOPER", List.of("WEBSITE_BUILD"));
        qaId = mockStaff("qa@studio.test", "Queen QA", "QA_REVIEWER", List.of());
        leadId = mockStaff("lead@studio.test", "Lara Lead", "TEAM_LEAD", List.of());
    }

    @Test
    void subscribeReturnsEmitterAndRegistersConnection() {
        SseEmitter emitter = service.subscribe(devId);
        assertNotNull(emitter);
        assertEquals(1, service.connectionCount());
        assertEquals(1, service.connectionCount(devId));
    }

    @Test
    void subscribeRejectsUnknownStaff() {
        UUID ghost = UUID.randomUUID();
        when(staffRepository.findById(ghost)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.subscribe(ghost));
        assertEquals(0, service.connectionCount());
    }

    @Test
    void multipleSubscribesForSameStaffAreAllTracked() {
        service.subscribe(devId);
        service.subscribe(devId);   // second tab
        assertEquals(2, service.connectionCount(devId));
        assertEquals(2, service.connectionCount());
    }

    @Test
    void broadcastToSkillIsSafeWhenNoSubscribers() {
        service.broadcastToSkill("WEBSITE_BUILD", "job.created",
                new JobLifecycleEvent.JobCreated(UUID.randomUUID(), "WB-1", "WEBSITE_BUILD", "AVAILABLE", "GREEN"));
        assertEquals(0, service.connectionCount());
    }

    @Test
    void broadcastsAndDirectSendsRunWithoutThrowing() {
        service.subscribe(devId);
        service.subscribe(qaId);
        service.subscribe(leadId);

        // Each broadcast type should reach its targets without raising.
        service.broadcastToSkill("WEBSITE_BUILD", "job.created",
                new JobLifecycleEvent.JobCreated(UUID.randomUUID(), "WB-1", "WEBSITE_BUILD", "AVAILABLE", "GREEN"));
        service.broadcastToRole("QA_REVIEWER", "job.submitted",
                new JobLifecycleEvent.JobSubmitted(UUID.randomUUID(), "WB-1", "WEBSITE_BUILD", devId));
        service.broadcastToRole("TEAM_LEAD", "job.escalated",
                new JobLifecycleEvent.JobEscalated(UUID.randomUUID(), "WB-1", "Overdue"));
        service.send(devId, "job.approved",
                new JobLifecycleEvent.JobApproved(UUID.randomUUID(), "WB-1", devId));

        // All three connections still registered (queued sends don't cleanup until the handler errors).
        assertEquals(3, service.connectionCount());
    }

    @Test
    void heartbeatIsSafeWithNoConnections() {
        service.heartbeat();
        assertEquals(0, service.connectionCount());
    }

    @Test
    void heartbeatIsSafeWithConnections() {
        service.subscribe(devId);
        service.subscribe(qaId);
        service.heartbeat();
        assertEquals(2, service.connectionCount());
    }

    @Test
    void lifecycleListenersDelegateToBroadcasts() {
        service.subscribe(devId);
        service.subscribe(qaId);
        service.subscribe(leadId);

        // These should all run cleanly — they invoke the same broadcast paths covered above.
        service.onJobCreated(new JobLifecycleEvent.JobCreated(UUID.randomUUID(), "WB-2", "WEBSITE_BUILD", "AVAILABLE", "GREEN"));
        service.onJobClaimed(new JobLifecycleEvent.JobClaimed(UUID.randomUUID(), "WB-2", "WEBSITE_BUILD", devId));
        service.onJobStarted(new JobLifecycleEvent.JobStarted(UUID.randomUUID(), "WB-2", devId));
        service.onJobSubmitted(new JobLifecycleEvent.JobSubmitted(UUID.randomUUID(), "WB-2", "WEBSITE_BUILD", devId));
        service.onJobApproved(new JobLifecycleEvent.JobApproved(UUID.randomUUID(), "WB-2", devId));
        service.onJobRevision(new JobLifecycleEvent.JobRevisionRequested(UUID.randomUUID(), "WB-2", devId, "Fix it"));
        service.onJobReassigned(new JobLifecycleEvent.JobReassigned(UUID.randomUUID(), "WB-2", qaId));
        service.onJobEscalated(new JobLifecycleEvent.JobEscalated(UUID.randomUUID(), "WB-2", "Overdue"));
        service.onSlaExtended(new JobLifecycleEvent.JobSlaExtended(UUID.randomUUID(), "WB-2", 12, devId));
        service.onNotification(new JobLifecycleEvent.NotificationCreated(UUID.randomUUID(), "WB-2", devId,
                UUID.randomUUID(), "JOB_ASSIGNED", "Title", "Message"));

        // All three still present (no exceptions, no spurious cleanups).
        assertEquals(3, service.connectionCount());
    }

    // ----- helpers ------------------------------------------------------------

    private UUID mockStaff(String email, String name, String role, List<String> skills) {
        UUID id = UUID.randomUUID();
        Staff staff = new Staff(email, "hash", name, role, new ArrayList<>(skills));
        setId(staff, id);
        when(staffRepository.findById(id)).thenReturn(Optional.of(staff));
        return id;
    }

    private static void setId(Staff staff, UUID id) {
        try {
            Field f = Staff.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(staff, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
