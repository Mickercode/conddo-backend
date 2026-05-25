package io.conddo.core.repository;

import io.conddo.core.domain.OrderPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderPaymentRepository extends JpaRepository<OrderPayment, UUID> {

    List<OrderPayment> findByOrderIdOrderByPaidAtDesc(UUID orderId);

    /** Total paid in the window (RLS-scoped). Used by the dashboard revenue KPI. */
    @Query("select coalesce(sum(p.amount), 0) from OrderPayment p "
            + "where p.paidAt >= :start and p.paidAt < :end")
    BigDecimal sumAmountBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /** Individual payments in the window, for analytics time-series bucketing (§11.9). */
    List<OrderPayment> findByPaidAtBetween(OffsetDateTime start, OffsetDateTime end);

    /** All payments across a customer's orders, newest first (§11.3 profile payment history). */
    @Query("select p from OrderPayment p where p.orderId in "
            + "(select o.id from Order o where o.customerId = :customerId) order by p.paidAt desc")
    List<OrderPayment> findByCustomerId(@Param("customerId") UUID customerId);

    /** Paid-to-date per order: rows of [orderId, sum(amount)] (RLS-scoped). Drives §11.7 balances. */
    @Query("select p.orderId, coalesce(sum(p.amount), 0) from OrderPayment p group by p.orderId")
    List<Object[]> sumByOrder();

    /** Every payment, newest first — the §11.7 transactions ledger (RLS-scoped). */
    List<OrderPayment> findAllByOrderByPaidAtDesc();
}
