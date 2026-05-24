package io.conddo.core.repository;

import io.conddo.core.domain.MarketingCampaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** RLS scopes every query to the current tenant — no manual tenant filter. */
public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, UUID> {

    List<MarketingCampaign> findAllByOrderByCreatedAtDesc();
}
