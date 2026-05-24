package io.conddo.core.repository;

import io.conddo.core.domain.OrderPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderPaymentRepository extends JpaRepository<OrderPayment, UUID> {

    List<OrderPayment> findByOrderIdOrderByPaidAtDesc(UUID orderId);
}
