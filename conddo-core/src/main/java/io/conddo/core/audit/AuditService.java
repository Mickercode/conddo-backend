package io.conddo.core.audit;

import io.conddo.core.domain.AuditLog;
import io.conddo.core.repository.AuditLogRepository;
import io.conddo.core.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Records audit events (PRD §6.5). Each write runs in its OWN transaction
 * ({@code REQUIRES_NEW}) so the audit row survives even when the business
 * operation is rolled back or rejected — essential for recording failures (e.g.
 * a failed login). The append-only RLS policy (V8) accepts these inserts without
 * a bound tenant, so the new transaction needs no {@code app.tenant_id}.
 *
 * <p>Both public {@code record} methods are entry points called from other
 * beans, so the {@code REQUIRES_NEW} proxy applies; they delegate to the private
 * {@code persist} (no self-invoked transactional call).
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Records an event for the current authenticated request — tenant from
     * {@code TenantContext}, actor from {@code AuditContext}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, UUID resourceId,
                       Map<String, Object> before, Map<String, Object> after) {
        persist(action, resourceType, resourceId,
                TenantContext.get().orElse(null), AuditContext.getActor().orElse(null), before, after);
    }

    /**
     * Records an event with an explicit tenant/actor — for flows where they are
     * not (yet) in context, e.g. login.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, UUID resourceId, UUID tenantId, UUID userId,
                       Map<String, Object> before, Map<String, Object> after) {
        persist(action, resourceType, resourceId, tenantId, userId, before, after);
    }

    private void persist(String action, String resourceType, UUID resourceId, UUID tenantId, UUID userId,
                         Map<String, Object> before, Map<String, Object> after) {
        auditLogRepository.save(new AuditLog(
                action, resourceType, resourceId, tenantId, userId,
                AuditContext.getIpAddress(), AuditContext.getUserAgent(), before, after));
    }
}
