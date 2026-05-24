package io.conddo.core.repository;

import io.conddo.core.domain.MarketingConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** RLS scopes every query to the current tenant — no manual tenant filter. */
public interface MarketingConnectionRepository extends JpaRepository<MarketingConnection, UUID> {

    List<MarketingConnection> findAllByOrderByPlatform();

    Optional<MarketingConnection> findByPlatform(String platform);
}
