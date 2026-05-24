package io.conddo.core.repository;

import io.conddo.core.domain.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * RLS scopes every query to the current tenant, so {@code findAll(spec)} and the
 * counts below already see only this tenant's orders — no manual tenant filter.
 */
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Orders not in a terminal stage — the dashboard's "pending orders" KPI. */
    @Query("select count(o) from Order o where lower(o.stage) not in :terminalStages")
    long countPending(@Param("terminalStages") Collection<String> terminalStages);

    /** Pending orders past their due date — the "needs attention" delta. */
    @Query("select count(o) from Order o where o.dueDate < :today "
            + "and lower(o.stage) not in :terminalStages")
    long countOverdue(@Param("today") LocalDate today,
                      @Param("terminalStages") Collection<String> terminalStages);

    // ----- analytics (§11.9) --------------------------------------------------

    /** Orders created in the window, for the orders time-series. */
    List<Order> findByCreatedAtBetween(OffsetDateTime start, OffsetDateTime end);

    /** Customers (by id) with more than one order — a "returning" proxy. */
    @Query("select o.customerId from Order o where o.customerId is not null "
            + "group by o.customerId having count(o) > 1")
    List<UUID> returningCustomerIds();

    /** Top services by order count: rows of [service, count]. */
    @Query("select o.service, count(o) from Order o where o.service is not null "
            + "group by o.service order by count(o) desc")
    List<Object[]> topServices(Pageable pageable);

    /** Top customers by order count: rows of [customerName, count]. */
    @Query("select o.customerName, count(o) from Order o where o.customerName is not null "
            + "group by o.customerName order by count(o) desc")
    List<Object[]> topCustomers(Pageable pageable);
}
