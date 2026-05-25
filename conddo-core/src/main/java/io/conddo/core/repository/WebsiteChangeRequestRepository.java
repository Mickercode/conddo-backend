package io.conddo.core.repository;

import io.conddo.core.domain.WebsiteChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** RLS scopes every query to the current tenant — no manual tenant filter. */
public interface WebsiteChangeRequestRepository extends JpaRepository<WebsiteChangeRequest, UUID> {

    /** The tenant's change requests, newest first (§11.2). */
    List<WebsiteChangeRequest> findAllByOrderByCreatedAtDesc();
}
