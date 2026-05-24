package io.conddo.core.repository;

import io.conddo.core.domain.StockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {
}
