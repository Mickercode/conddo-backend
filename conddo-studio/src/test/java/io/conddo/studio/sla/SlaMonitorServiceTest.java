package io.conddo.studio.sla;

import io.conddo.studio.domain.Job;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.sse.SseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SLA monitor ticks: builds the right snapshot of amber + red jobs, auto-escalates
 * anything past the deadline (the existing escalate() pipeline handles fan-out),
 * and stays silent on a fully-green board.
 */
class SlaMonitorServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-03T09:00:00Z");
    private static final OffsetDateTime NOW_ODT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final SseService sseService = mock(SseService.class);
    private final SlaMonitorService monitor = new SlaMonitorService(
            jobRepository, jobService, sseService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void mixedBoardTriggersSnapshotAndAutoEscalation() {
        Job green = job("WB-1", "IN_PROGRESS", NOW_ODT.plusHours(48), UUID.randomUUID());
        Job amber = job("WB-2", "IN_PROGRESS", NOW_ODT.plusHours(10), UUID.randomUUID());
        Job red = job("WB-3", "IN_PROGRESS", NOW_ODT.plusHours(2), UUID.randomUUID());
        Job overdue = job("WB-4", "SUBMITTED", NOW_ODT.minusHours(3), UUID.randomUUID());

        when(jobRepository.findByStatusInOrderBySlaDeadlineAsc(SlaMonitorService.ACTIVE_STATUSES))
                .thenReturn(List.of(green, amber, red, overdue));
        when(jobService.slaTone(green)).thenReturn("GREEN");
        when(jobService.slaTone(amber)).thenReturn("AMBER");
        when(jobService.slaTone(red)).thenReturn("RED");
        when(jobService.slaTone(overdue)).thenReturn("RED");

        monitor.tick();

        // Auto-escalation runs for the overdue job (not the still-future red one).
        verify(jobService).escalate(eq(overdue.getId()), contains("Auto-escalated"));
        verify(jobService, never()).escalate(eq(red.getId()), anyString());
        verify(jobService, never()).escalate(eq(amber.getId()), anyString());
        verify(jobService, never()).escalate(eq(green.getId()), anyString());

        // Snapshot covers all three non-green jobs; broadcast hits leads + admins.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SlaMonitorService.SlaSnapshotItem>> snap =
                ArgumentCaptor.forClass(List.class);
        verify(sseService).broadcastToRole(eq("TEAM_LEAD"), eq("sla.tick"), snap.capture());
        verify(sseService).broadcastToRole(eq("ADMIN"), eq("sla.tick"), any());

        Map<String, SlaMonitorService.SlaSnapshotItem> byNumber = snap.getValue().stream()
                .collect(java.util.stream.Collectors.toMap(SlaMonitorService.SlaSnapshotItem::jobNumber, i -> i));
        assertEquals(3, byNumber.size());
        assertEquals("AMBER", byNumber.get("WB-2").tone());
        assertEquals("RED", byNumber.get("WB-3").tone());
        assertEquals("RED", byNumber.get("WB-4").tone());
        assertTrue(byNumber.get("WB-4").hoursToDeadline() < 0, "overdue tracks negative hours");
    }

    @Test
    void allGreenBoardEmitsNothing() {
        Job a = job("WB-1", "IN_PROGRESS", NOW_ODT.plusHours(48), UUID.randomUUID());
        Job b = job("WB-2", "ASSIGNED", NOW_ODT.plusHours(30), UUID.randomUUID());
        when(jobRepository.findByStatusInOrderBySlaDeadlineAsc(SlaMonitorService.ACTIVE_STATUSES))
                .thenReturn(List.of(a, b));
        when(jobService.slaTone(a)).thenReturn("GREEN");
        when(jobService.slaTone(b)).thenReturn("GREEN");

        monitor.tick();

        verifyNoInteractions(sseService);
        verify(jobService, never()).escalate(any(), anyString());
    }

    @Test
    void emptyBoardIsNoop() {
        when(jobRepository.findByStatusInOrderBySlaDeadlineAsc(SlaMonitorService.ACTIVE_STATUSES))
                .thenReturn(List.of());

        monitor.tick();

        verifyNoInteractions(sseService);
        verify(jobService, never()).escalate(any(), anyString());
    }

    @Test
    void onlyOverdueRedSendsBothBroadcasts() {
        Job overdue = job("WB-9", "IN_PROGRESS", NOW_ODT.minusHours(1), UUID.randomUUID());
        when(jobRepository.findByStatusInOrderBySlaDeadlineAsc(SlaMonitorService.ACTIVE_STATUSES))
                .thenReturn(List.of(overdue));
        when(jobService.slaTone(overdue)).thenReturn("RED");

        monitor.tick();

        verify(sseService).broadcastToRole(eq("TEAM_LEAD"), eq("sla.tick"), any());
        verify(sseService).broadcastToRole(eq("ADMIN"), eq("sla.tick"), any());
        verify(jobService, times(1)).escalate(eq(overdue.getId()), anyString());
    }

    // ----- helpers ------------------------------------------------------------

    private static Job job(String number, String status, OffsetDateTime slaDeadline, UUID assignedTo) {
        Job job = new Job(number, "WEBSITE_BUILD", UUID.randomUUID(), number,
                Map.of(), status, slaDeadline);
        setField(job, "id", UUID.randomUUID());
        if (assignedTo != null) {
            setField(job, "assignedTo", assignedTo);
        }
        return job;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = Job.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
