package io.conddo.studio.repository;

import io.conddo.studio.domain.StaffPerformance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffPerformanceRepository extends JpaRepository<StaffPerformance, UUID> {

    Optional<StaffPerformance> findByStaffIdAndPeriodMonth(UUID staffId, LocalDate periodMonth);

    /** All snapshots for a month, sorted highest-completed first — powers the team performance board. */
    List<StaffPerformance> findByPeriodMonthOrderByJobsCompletedDesc(LocalDate periodMonth);

    /** Recent months for one staff member (admin sees history). Returned newest-first. */
    List<StaffPerformance> findTop12ByStaffIdOrderByPeriodMonthDesc(UUID staffId);
}
