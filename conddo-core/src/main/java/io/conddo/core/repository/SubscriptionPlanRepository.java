package io.conddo.core.repository;

import io.conddo.core.domain.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    Optional<SubscriptionPlan> findByName(String name);

    List<SubscriptionPlan> findByActiveTrueOrderByMonthlyPriceAsc();
}
