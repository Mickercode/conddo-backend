package io.conddo.core.signup;

import java.util.UUID;

/**
 * Published by {@link io.conddo.core.service.TenantService} right after a tenant
 * is persisted on signup. Consumed (after-commit, fail-safe) to auto-create the
 * tenant's initial Studio job and any other downstream provisioning.
 *
 * <p>Only the {@code tenantId} is carried — listeners load the {@link
 * io.conddo.core.domain.Tenant} themselves so they always see the committed state.
 */
public record TenantActivatedEvent(UUID tenantId) {
}
