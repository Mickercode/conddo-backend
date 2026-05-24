package io.conddo.core.repository;

import io.conddo.core.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Note: there is intentionally no {@code findByTenantId(...)} here. RLS scopes
 * results to the current tenant automatically, so {@code findAll()} (and the
 * Specification queries) already return only this tenant's customers. Manual
 * tenant filtering would be both redundant and a place for bugs.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    /** Customers created in the window (RLS-scoped). Used by the dashboard KPI. */
    @Query("select count(c) from Customer c where c.createdAt >= :start and c.createdAt < :end")
    long countCreatedBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /** Customers created in the window, for the analytics new-customer series (§11.9). */
    java.util.List<Customer> findByCreatedAtBetween(OffsetDateTime start, OffsetDateTime end);
}
