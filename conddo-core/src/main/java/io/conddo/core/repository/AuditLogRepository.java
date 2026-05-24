package io.conddo.core.repository;

import io.conddo.core.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only in practice (V8 revokes UPDATE/DELETE). Reads are tenant-scoped by
 * RLS; there is intentionally no {@code findByTenantId} — a future audit-view
 * relies on the policy, not manual filtering.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** A staff member's recent actions (§11.10), newest first. RLS scopes to the tenant. */
    List<AuditLog> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);
}
