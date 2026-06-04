package io.conddo.payments.routepay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default {@link RoutePayClient} — used when {@code routepay.client-id} is
 * blank. Lets the service boot in dev / pre-credential Render deploys without
 * a working RoutePay account.
 *
 * <p>Returns deterministic placeholder values so the surrounding code (DB
 * writes, tenant-account provisioning state machine, webhook idempotency log)
 * exercises end-to-end without a network call. The PaymentService docstrings
 * are explicit about the dormant behaviour so an operator knows their charges
 * aren't actually going to RoutePay.
 */
@Component
public class DormantRoutePayClient implements RoutePayClient {

    private static final Logger log = LoggerFactory.getLogger(DormantRoutePayClient.class);

    public DormantRoutePayClient() {
        log.info("RoutePay client is dormant — no ROUTEPAY_CLIENT_ID set. "
                + "Charges will not be routed to RoutePay until credentials are configured.");
    }

    @Override
    public SubAccountResult createSubAccount(UUID tenantId, String businessName, String contactEmail) {
        // Deterministic so the same tenant always lands on the same fake sub-account
        // (helps make tests stable).
        return new SubAccountResult("dormant-sub-" + tenantId);
    }

    @Override
    public InitPaymentResult initPayment(InitPaymentRequest request) {
        // Return a clearly-fake URL so a human eyeballing logs knows nothing real
        // is being charged.
        return new InitPaymentResult("dormant-txn-" + request.reference(),
                "https://payments.conddo.io/dormant/" + request.reference());
    }

    @Override
    public GetTransactionResult getTransaction(String routepayReference) {
        // Always reports PENDING — the real adapter is needed to terminalise.
        return new GetTransactionResult("dormant-txn-" + routepayReference, "PENDING", null, null);
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
