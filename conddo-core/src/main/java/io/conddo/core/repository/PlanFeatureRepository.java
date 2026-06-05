package io.conddo.core.repository;

import io.conddo.core.domain.PlanFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanFeatureRepository extends JpaRepository<PlanFeature, UUID> {

    List<PlanFeature> findByPlanId(UUID planId);

    Optional<PlanFeature> findByPlanIdAndFeatureKey(UUID planId, String featureKey);
}
