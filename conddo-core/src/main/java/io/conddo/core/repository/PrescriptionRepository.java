package io.conddo.core.repository;

import io.conddo.core.domain.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped via RLS — every query already sees only the current tenant's
 * prescriptions. The status-bucket counts in {@code summary()} are aggregated
 * here so a single DB hit feeds the dashboard tile.
 */
public interface PrescriptionRepository
        extends JpaRepository<Prescription, UUID>, JpaSpecificationExecutor<Prescription> {

    List<Prescription> findByCustomerIdOrderByIssuedAtDesc(UUID customerId);

    /** Cheap summary counts — drives the "Prescriptions" dashboard tile. */
    @Query("""
            select new io.conddo.core.repository.PrescriptionRepository$SummaryRow(
                count(p),
                sum(case when p.refillIntervalDays is null then 1 else 0 end),
                sum(case when p.refillIntervalDays is not null
                         and p.nextRefillDue is not null
                         and p.nextRefillDue between :today and :soonCutoff then 1 else 0 end),
                sum(case when p.refillIntervalDays is not null
                         and p.nextRefillDue is not null
                         and p.nextRefillDue < :today then 1 else 0 end))
            from Prescription p
            """)
    SummaryRow summary(@Param("today") LocalDate today, @Param("soonCutoff") LocalDate soonCutoff);

    /** Cron-driven (Phase 2) — prescriptions due within {@code window} where reminder is stale. */
    @Query("""
            select p from Prescription p
            where p.refillIntervalDays is not null
              and p.nextRefillDue between :today and :windowEnd
              and (p.lastRemindedAt is null or p.lastRemindedAt < :remindCutoff)
            """)
    List<Prescription> findDueForReminder(@Param("today") LocalDate today,
                                          @Param("windowEnd") LocalDate windowEnd,
                                          @Param("remindCutoff") java.time.OffsetDateTime remindCutoff);

    /** Projection record for the summary aggregate. */
    record SummaryRow(long total, Long oneOff, Long dueSoon, Long overdue) {
    }
}
