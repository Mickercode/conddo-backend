package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PharmacyReminderRepository extends JpaRepository<PharmacyReminder, UUID>,
        JpaSpecificationExecutor<PharmacyReminder> {

    /**
     * Cross-tenant due-queue read used by the hourly scheduler. The
     * scheduler sets {@code app.cross_tenant = true} (V26 carve-out)
     * before this call so RLS lets it walk every tenant's rows.
     */
    @Query("select r from PharmacyReminder r where r.status = 'SCHEDULED' "
            + "and r.scheduledAt <= :now "
            + "order by r.scheduledAt")
    List<PharmacyReminder> findDueAcrossTenants(@Param("now") OffsetDateTime now);

    List<PharmacyReminder> findByCustomerIdOrderByScheduledAtDesc(UUID customerId);
}
