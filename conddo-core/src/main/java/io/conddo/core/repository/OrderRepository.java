package io.conddo.core.repository;

import io.conddo.core.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * RLS scopes every query to the current tenant, so {@code findAll(spec)} and the
 * counts below already see only this tenant's orders — no manual tenant filter.
 */
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {
}
