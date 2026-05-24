package io.conddo.core.repository;

import io.conddo.core.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsBySlug(String slug);

    Optional<Tenant> findBySlug(String slug);

    /** Resolves a tenant from its self-book link slug (§11.5 public endpoint). */
    Optional<Tenant> findByBookingLinkSlug(String bookingLinkSlug);
}
