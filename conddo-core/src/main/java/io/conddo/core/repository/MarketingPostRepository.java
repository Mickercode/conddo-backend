package io.conddo.core.repository;

import io.conddo.core.domain.MarketingPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** RLS scopes every query to the current tenant — no manual tenant filter. */
public interface MarketingPostRepository extends JpaRepository<MarketingPost, UUID> {

    List<MarketingPost> findByScheduledAtBetweenOrderByScheduledAt(OffsetDateTime from, OffsetDateTime to);

    List<MarketingPost> findAllByOrderByScheduledAtAsc();

    List<MarketingPost> findByScheduledAtGreaterThanEqualOrderByScheduledAt(OffsetDateTime from);
}
