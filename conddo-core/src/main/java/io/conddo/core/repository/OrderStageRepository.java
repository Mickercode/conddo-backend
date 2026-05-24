package io.conddo.core.repository;

import io.conddo.core.domain.OrderStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderStageRepository extends JpaRepository<OrderStage, UUID> {

    /** This tenant's stored stages in pipeline order (RLS scopes to the tenant). */
    List<OrderStage> findAllByOrderByPositionAsc();

    Optional<OrderStage> findByName(String name);
}
