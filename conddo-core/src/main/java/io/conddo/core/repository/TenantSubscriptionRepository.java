package io.conddo.core.repository;

import io.conddo.core.domain.TenantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscriptions aren't RLS-scoped — billing decisions need to see every
 * row per tenant (active + historical) and run inside a privileged service.
 * The repository methods filter explicitly by tenantId.
 */
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {

    /** The single live subscription for this tenant — null if no row matches. */
    @Query("""
            select s from TenantSubscription s
            where s.tenantId = :tenantId
              and s.status in ('trialing','active','grace')
            """)
    Optional<TenantSubscription> findActiveByTenantId(@Param("tenantId") UUID tenantId);

    List<TenantSubscription> findByTenantIdOrderByStartedAtDesc(UUID tenantId);

    /**
     * Candidates for the hourly expiry scan
     * ({@link io.conddo.core.service.BillingService#runExpiryScan}). Returns
     * every live subscription whose {@code expires_at} has passed, OR which
     * has been marked cancelled but not yet finalized — the per-row
     * transition logic in {@code applyExpiryTransitions} sorts out grace vs
     * expired vs cancelled-completion.
     */
    @Query("""
            select s from TenantSubscription s
            where s.status in ('trialing','active','grace')
              and (s.expiresAt < :now or s.cancelledAt is not null)
            """)
    List<TenantSubscription> findExpirable(@Param("now") java.time.OffsetDateTime now);
}
