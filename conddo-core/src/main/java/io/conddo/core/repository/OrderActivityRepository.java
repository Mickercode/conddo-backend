package io.conddo.core.repository;

import io.conddo.core.domain.OrderActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderActivityRepository extends JpaRepository<OrderActivity, UUID> {

    List<OrderActivity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
