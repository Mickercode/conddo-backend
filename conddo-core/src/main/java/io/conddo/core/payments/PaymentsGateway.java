package io.conddo.core.payments;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for talking to <b>Conddo Payments</b> — the standalone payments service
 * that wraps RoutePay (ACTION_LIST §7a). The platform (Control plane) tells
 * payments who a tenant is; payments owns the money flow.
 *
 * <p>Same shape as {@link io.conddo.core.studio.StudioJobGateway}: HTTP call
 * implemented in {@code conddo-api}, fail-safe (returns
 * {@link Optional#empty()} on transport error so signup never depends on the
 * payments service being up).
 */
public interface PaymentsGateway {

    /**
     * Provision a tenant's RoutePay sub-account. Idempotent — re-calling for the
     * same {@code tenantId} returns the existing record. The handle's
     * {@code subaccountId} is null while the sub-account is
     * {@code PROVISIONING_FAILED}; in that case a manual retry from Studio Admin
     * or a periodic reconciliation job is expected to flip it.
     */
    Optional<TenantPaymentsAccount> provisionTenantAccount(UUID tenantId, String tenantSlug,
                                                           String businessName, String contactEmail);

    /** Minimal handle to a provisioned tenant account, returned across the seam. */
    record TenantPaymentsAccount(UUID tenantId, String subaccountId, String status) {
    }
}
