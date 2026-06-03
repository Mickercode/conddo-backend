package io.conddo.studio.performance;

import io.conddo.studio.domain.Staff;
import io.conddo.studio.domain.StaffPerformance;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.repository.QaReviewRepository;
import io.conddo.studio.repository.StaffPerformanceRepository;
import io.conddo.studio.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PerformanceService: the daily recalc walks every active staff member and
 * upserts the current month's row; inactive staff are skipped; first-pass rate
 * is computed correctly and defaults to 100 when no QA work has been done.
 */
class PerformanceServiceTest {

    private static final LocalDate JUNE_2026 = LocalDate.of(2026, 6, 1);
    private static final Clock FIXED = Clock.fixed(
            java.time.LocalDateTime.of(2026, 6, 15, 12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StaffPerformanceRepository perfRepository = mock(StaffPerformanceRepository.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final QaReviewRepository qaReviewRepository = mock(QaReviewRepository.class);

    private final PerformanceService service = new PerformanceService(
            staffRepository, perfRepository, jobRepository, qaReviewRepository, FIXED);

    @Test
    void recalcUpsertsCurrentMonthForOneStaffMember() {
        UUID staffId = UUID.randomUUID();
        when(jobRepository.countByAssignedToAndStatusIn(eq(staffId), anyList())).thenReturn(12L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(staffId, "APPROVED")).thenReturn(8L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(staffId, "REVISION")).thenReturn(2L);
        when(perfRepository.findByStaffIdAndPeriodMonth(staffId, JUNE_2026)).thenReturn(Optional.empty());
        when(perfRepository.save(any(StaffPerformance.class))).thenAnswer(inv -> inv.getArgument(0));

        StaffPerformance saved = service.recalc(staffId, JUNE_2026);

        ArgumentCaptor<StaffPerformance> captured = ArgumentCaptor.forClass(StaffPerformance.class);
        verify(perfRepository).save(captured.capture());
        StaffPerformance row = captured.getValue();
        assertEquals(staffId, row.getStaffId());
        assertEquals(JUNE_2026, row.getPeriodMonth());
        assertEquals(12, row.getJobsCompleted());
        assertEquals(15, row.getJobsTarget());
        assertEquals(2, row.getRevisionCount());
        // 8 approvals / (8 approvals + 2 revisions) = 80.00%
        assertEquals(new BigDecimal("80.00"), row.getFirstPassQaRate());
        assertSame(row, saved);
    }

    @Test
    void recalcWithNoReviewsDefaultsFirstPassTo100() {
        UUID staffId = UUID.randomUUID();
        when(jobRepository.countByAssignedToAndStatusIn(eq(staffId), anyList())).thenReturn(5L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(staffId, "APPROVED")).thenReturn(0L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(staffId, "REVISION")).thenReturn(0L);
        when(perfRepository.findByStaffIdAndPeriodMonth(staffId, JUNE_2026)).thenReturn(Optional.empty());
        when(perfRepository.save(any(StaffPerformance.class))).thenAnswer(inv -> inv.getArgument(0));

        StaffPerformance row = service.recalc(staffId, JUNE_2026);

        assertEquals(new BigDecimal("100"), row.getFirstPassQaRate());
    }

    @Test
    void recalcUpdatesExistingRowInPlace() {
        UUID staffId = UUID.randomUUID();
        StaffPerformance existing = perf(staffId, JUNE_2026);
        when(perfRepository.findByStaffIdAndPeriodMonth(staffId, JUNE_2026)).thenReturn(Optional.of(existing));
        when(jobRepository.countByAssignedToAndStatusIn(eq(staffId), anyList())).thenReturn(20L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(staffId, "APPROVED")).thenReturn(15L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(staffId, "REVISION")).thenReturn(5L);
        when(perfRepository.save(existing)).thenReturn(existing);

        service.recalc(staffId, JUNE_2026);

        assertEquals(20, existing.getJobsCompleted());
        assertEquals(5, existing.getRevisionCount());
        assertEquals(new BigDecimal("75.00"), existing.getFirstPassQaRate());
        verify(perfRepository).save(existing);
    }

    @Test
    void dailyRecalcSkipsInactiveStaff() {
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        Staff active = staffWith(activeId, true);
        Staff inactive = staffWith(inactiveId, false);
        when(staffRepository.findAll()).thenReturn(List.of(active, inactive));
        when(jobRepository.countByAssignedToAndStatusIn(eq(activeId), anyList())).thenReturn(0L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(activeId, "APPROVED")).thenReturn(0L);
        when(qaReviewRepository.countByReviewerIdAndOutcome(activeId, "REVISION")).thenReturn(0L);
        when(perfRepository.findByStaffIdAndPeriodMonth(eq(activeId), any())).thenReturn(Optional.empty());
        when(perfRepository.save(any(StaffPerformance.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dailyRecalc();

        verify(perfRepository, times(1)).save(any(StaffPerformance.class));
        verify(jobRepository, never()).countByAssignedToAndStatusIn(eq(inactiveId), anyList());
    }

    @Test
    void dailyRecalcOnEmptyTeamIsNoop() {
        when(staffRepository.findAll()).thenReturn(List.of());
        service.dailyRecalc();
        verify(perfRepository, never()).save(any(StaffPerformance.class));
    }

    @Test
    void currentTeamBoardReadsCurrentMonth() {
        StaffPerformance one = perf(UUID.randomUUID(), JUNE_2026);
        when(perfRepository.findByPeriodMonthOrderByJobsCompletedDesc(JUNE_2026)).thenReturn(List.of(one));

        List<StaffPerformance> board = service.currentTeamBoard();

        assertEquals(1, board.size());
        verify(perfRepository).findByPeriodMonthOrderByJobsCompletedDesc(JUNE_2026);
    }

    @Test
    void historyDelegatesToTop12Query() {
        UUID staffId = UUID.randomUUID();
        when(perfRepository.findTop12ByStaffIdOrderByPeriodMonthDesc(staffId)).thenReturn(List.of());
        service.history(staffId);
        verify(perfRepository).findTop12ByStaffIdOrderByPeriodMonthDesc(staffId);
    }

    // ----- helpers ------------------------------------------------------------

    private static Staff staffWith(UUID id, boolean active) {
        Staff staff = new Staff("x@studio.test", "hash", "Some One", "DEVELOPER", new ArrayList<>());
        setField(Staff.class, staff, "id", id);
        if (!active) {
            staff.setActive(false);
        }
        return staff;
    }

    private static StaffPerformance perf(UUID staffId, LocalDate month) {
        return new StaffPerformance(staffId, month);
    }

    private static void setField(Class<?> type, Object target, String name, Object value) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
