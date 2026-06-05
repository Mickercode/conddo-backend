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
}
