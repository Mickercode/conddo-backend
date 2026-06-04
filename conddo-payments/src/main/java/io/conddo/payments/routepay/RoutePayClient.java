package io.conddo.payments.routepay;

import java.util.UUID;

/**
 * Port for RoutePay's payment API (ACTION_LIST §7a). Two adapters:
 * <ul>
 *   <li>{@link DormantRoutePayClient} — used when no client credentials are
 *       configured. Returns deterministic stubs so the rest of the service
 *       runs and tests pass without a network. Logs once at startup.</li>
 *   <li>{@link HttpRoutePayClient} — wraps RoutePay's real HTTP API. Active
 *       only when {@code routepay.client-id} is set.</li>
 * </ul>
 *
 * <p>Per the §20 AI-rule pattern (which is just "never throw out of an
 * external port"), an HTTP failure surfaces as
 * {@link io.conddo.payments.common.RoutePayUnavailableException} so the
 * caller maps it to 502 — the customer sees "try again", not 500.
 */
public interface RoutePayClient {

    /**
     * Provision a sub-account for a tenant. RoutePay returns the new
     * sub-account id; we persist it on {@code tenant_accounts}.
     */
    SubAccountResult createSubAccount(UUID tenantId, String businessName, String contactEmail);

    /**
     * Set up a payment intent on RoutePay's hosted checkout, on behalf of a
     * tenant's sub-account. Returns the URL the customer visits.
     */
    InitPaymentResult initPayment(InitPaymentRequest request);

    /**
     * Fetch the current state of a payment from RoutePay. Used by the verify
     * endpoint when our local row is still PENDING and the FE needs a final
     * answer (customer just returned from the hosted checkout).
     */
    GetTransactionResult getTransaction(String routepayReference);

    /** {@code true} when real client credentials are configured. */
    boolean isConfigured();

    // ----- DTOs --------------------------------------------------------------

    record SubAccountResult(String subaccountId) {
    }

    record InitPaymentRequest(String reference, long amountKobo, String customerEmail,
                              String customerName, String description, String returnUrl,
                              String tenantSubaccountId, int platformFeeBps) {
    }

    record InitPaymentResult(String routepayTransactionRef, String paymentUrl) {
    }

    record GetTransactionResult(String routepayTransactionRef, String status, Long feeKobo,
                                String failureReason) {
    }
}
