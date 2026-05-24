package io.conddo.core.repository;

import io.conddo.core.domain.MarketingLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/** RLS scopes every query to the current tenant — no manual tenant filter. */
public interface MarketingLeadRepository extends JpaRepository<MarketingLead, UUID> {

    List<MarketingLead> findAllByOrderByCreatedAtDesc();

    List<MarketingLead> findByStageOrderByCreatedAtDesc(String stage);

    /** Lead counts grouped by funnel stage: rows of [stage, count]. */
    @Query("select l.stage, count(l) from MarketingLead l group by l.stage")
    List<Object[]> countByStage();
}
