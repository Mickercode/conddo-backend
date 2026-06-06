package io.conddo.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Calls back to conddo-api after a webhook lands, so the platform can flip
 * the order / booking to PAID, fire an in-app notification to the tenant,
 * and tick the tenant ledger. Uses the same {@code X-Payments-Service-Token}
 * in reverse — conddo-api trusts inbound calls from us with that header.
 *
 * <p>Fail-safe: a missing {@code CONDDO_API_BASE_URL} disables the callback
 * (we still process the webhook locally), and HTTP failures are logged but
 * never thrown — the webhook flow continues, and a manual reconciliation
 * job (future) can catch any drift.
 */
@Component
public class ConddoApiNotifyClient {

    private static final Logger log = LoggerFactory.getLogger(ConddoApiNotifyClient.class);

    private final RestClient restClient;
    private final boolean configured;

    public ConddoApiNotifyClient(@Value("${payments.api.base-url:}") String baseUrl,
                                 @Value("${payments.service.token:}") String serviceToken,
                                 RestClient.Builder builder) {
        this.configured = baseUrl != null && !baseUrl.isBlank()
                && serviceToken != null && !serviceToken.isBlank();
        this.restClient = configured ? builder.baseUrl(baseUrl)
                .defaultHeader("X-Payments-Service-Token", serviceToken)
                .build() : null;
        if (!configured) {
            log.warn("ConddoApiNotifyClient is dormant — CONDDO_API_BASE_URL or PAYMENTS_SERVICE_TOKEN is blank");
        }
    }

    /**
     * Notify conddo-api that a payment landed (or failed). Idempotent on their
     * side. V2 added the {@code creativeRequestId} + {@code brandPackageSubscriptionId}
     * + {@code paymentReference} fields — conddo-api uses {@code paymentReference}
     * to look up creative-service / brand-package rows (V1 only had orderId/
     * bookingId; that path still works for back-compat).
     */
    public void notifyPayment(UUID tenantId, UUID paymentId, String status,
                              UUID orderId, UUID bookingId,
                              UUID creativeRequestId, UUID brandPackageSubscriptionId,
                              String paymentReference, long amountKobo) {
        if (!configured) {
            return;
        }
        try {
            // LinkedHashMap (not Map.of) because at most one of the four target
            // ids is set per row; Map.of refuses null values.
            java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("paymentId", paymentId);
            body.put("status", status);
            body.put("orderId", orderId);
            body.put("bookingId", bookingId);
            body.put("creativeRequestId", creativeRequestId);
            body.put("brandPackageSubscriptionId", brandPackageSubscriptionId);
            body.put("paymentReference", paymentReference);
            body.put("amountKobo", amountKobo);
            restClient.post()
                    .uri("/api/v1/internal/payments/notify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.error("conddo-api payment notify failed for payment {}: {}", paymentId, ex.getMessage());
        }
    }

    public boolean isConfigured() {
        return configured;
    }
}
