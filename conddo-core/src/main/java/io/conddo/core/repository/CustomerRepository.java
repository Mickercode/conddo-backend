package io.conddo.core.repository;

import io.conddo.core.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Note: there is intentionally no {@code findByTenantId(...)} here. RLS scopes
 * results to the current tenant automatically, so {@code findAll()} (and the
 * Specification queries) already return only this tenant's customers. Manual
 * tenant filtering would be both redundant and a place for bugs.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {
}
