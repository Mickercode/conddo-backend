package io.conddo.payments.domain;

/**
 * Tenant RoutePay sub-account lifecycle:
 * <ul>
 *   <li>{@code DEPOSIT_PENDING} — sub-account exists but no settlement bank
 *       on file yet, so payouts are held. Tenant must add bank details in
 *       Settings → Payments before accepting their first payment.</li>
 *   <li>{@code ACTIVE} — accepting payments, settling to the tenant's bank.</li>
 *   <li>{@code SUSPENDED} — Studio admin (§23) flipped status to SUSPENDED, or
 *       RoutePay flagged the sub-account. New payment init refuses.</li>
 *   <li>{@code PROVISIONING_FAILED} — initial sub-account create failed at
 *       signup; manual retry via {@code POST /internal/tenants/{id}/retry}.</li>
 * </ul>
 */
public enum TenantAccountStatus {
    DEPOSIT_PENDING,
    ACTIVE,
    SUSPENDED,
    PROVISIONING_FAILED;

    public boolean canAcceptPayments() {
        // Accept while DEPOSIT_PENDING so the tenant can collect; settlement
        // happens once bank details land (RoutePay holds the balance internally).
        return this == ACTIVE || this == DEPOSIT_PENDING;
    }
}
