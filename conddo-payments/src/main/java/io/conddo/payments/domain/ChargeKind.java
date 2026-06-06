package io.conddo.payments.domain;

/**
 * What the payment is for — drives the RoutePay routing decision.
 * Merchant-customer charges ({@link #BOOKING_DEPOSIT}, {@link #ORDER}) fan
 * out to the tenant's RoutePay sub-account (the merchant gets paid).
 * Platform charges ({@link #CREATIVE_SERVICE}, {@link #BRAND_PACKAGE})
 * land directly in Conddo's master account (the merchant pays Conddo).
 *
 * <p>Mirrors the {@code charge_kind} CHECK constraint added in V2.
 */
public enum ChargeKind {

    BOOKING_DEPOSIT(true),
    ORDER(true),
    CREATIVE_SERVICE(false),
    BRAND_PACKAGE(false);

    /** True when the charge should route to the tenant's sub-account. */
    private final boolean routesToTenantSubaccount;

    ChargeKind(boolean routesToTenantSubaccount) {
        this.routesToTenantSubaccount = routesToTenantSubaccount;
    }

    public boolean routesToTenantSubaccount() {
        return routesToTenantSubaccount;
    }
}
