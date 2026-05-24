package io.conddo.core.repository;

import io.conddo.core.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Append-only in practice (V8 revokes UPDATE/DELETE). Reads are tenant-scoped by
 * RLS; there is intentionally no {@code findByTenantId} — a future audit-view
 * relies on the policy, not manual filtering.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
