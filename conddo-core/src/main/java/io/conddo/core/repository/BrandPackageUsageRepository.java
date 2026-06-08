package io.conddo.core.repository;

import io.conddo.core.domain.BrandPackageUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface BrandPackageUsageRepository extends JpaRepository<BrandPackageUsage, UUID> {

    Optional<BrandPackageUsage> findBySubscriptionIdAndPeriodStart(UUID subscriptionId, OffsetDateTime periodStart);

    /**
     * Current-period usage row for a subscription — the row whose
     * {@code period_start} is the most recent one we've opened. Returns
     * empty when the subscription has just been created and no quota has
     * been consumed yet.
     */
    Optional<BrandPackageUsage> findFirstBySubscriptionIdOrderByPeriodStartDesc(UUID subscriptionId);
}
