package io.conddo.payments.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.payments.common.ConflictException;
import io.conddo.payments.common.NotFoundException;
import io.conddo.payments.domain.Payment;
import io.conddo.payments.domain.PaymentStatus;
import io.conddo.payments.domain.TenantAccount;
import io.conddo.payments.domain.WebhookEvent;
import io.conddo.payments.repository.PaymentRepository;
import io.conddo.payments.repository.TenantAccountRepository;
import io.conddo.payments.repository.WebhookEventRepository;
import io.conddo.payments.routepay.RoutePayClient;
import io.conddo.payments.routepay.RoutePayWebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The payments orchestrator (ACTION_LIST §7a). Three core flows:
 * <ol>
 *   <li>{@link #provisionTenantAccount(UUID, String, String, String)} — called
 *       once per tenant at signup. Asks RoutePay for a sub-account and persists
 *       the result. Idempotent: re-calling for the same tenant returns the
 *       existing row.</li>
 *   <li>{@link #initPayment(UUID, String, InitPaymentInput)} — generate a
 *       routepay reference, ask RoutePay for a checkout URL, persist
 *       {@code Payment} row in {@code PENDING}, return checkout URL to the
 *       FE.</li>
 *   <li>{@link #handleWebhook(byte[], String)} — verify signature, dedupe,
 *       terminalise the {@code Payment} status, notify conddo-api.</li>
 * </ol>
 *
 * <p>Tenant scoping is enforced in code (no RLS here). Every read filters by
 * {@code tenantId} from the JWT; service-token endpoints pass {@code tenantId}
 * explicitly.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TenantAccountRepository tenantAccounts;
    private final PaymentRepository payments;
    private final WebhookEventRepository webhookEvents;
    private final RoutePayClient routePay;
    private final RoutePayWebhookVerifier webhookVerifier;
    private final ConddoApiNotifyClient notifyClient;
    private final ObjectMapper objectMapper;
    private final int platformFeeBps;

    public PaymentService(TenantAccountRepository tenantAccounts,
                          PaymentRepository payments,
                          WebhookEventRepository webhookEvents,
                          RoutePayClient routePay,
                          RoutePayWebhookVerifier webhookVerifier,
                          ConddoApiNotifyClient notifyClient,
                          ObjectMapper objectMapper,
                          @Value("${routepay.platform-fee-bps:250}") int platformFeeBps) {
        this.tenantAccounts = tenantAccounts;
        this.payments = payments;
        this.webhookEvents = webhookEvents;
        this.routePay = routePay;
        this.webhookVerifier = webhookVerifier;
        this.notifyClient = notifyClient;
        this.objectMapper = objectMapper;
        this.platformFeeBps = platformFeeBps;
    }

    // ----- tenant provisioning -----------------------------------------------

    @Transactional
    public TenantAccount provisionTenantAccount(UUID tenantId, String tenantSlug,
                                                String businessName, String contactEmail) {
        Optional<TenantAccount> existing = tenantAccounts.findById(tenantId);
        if (existing.isPresent()) {
            // Idempotent: a re-provision attempt for an already-active tenant is a no-op.
            return existing.get();
        }
        TenantAccount account = new TenantAccount(tenantId, tenantSlug, businessName, contactEmail);
        try {
            RoutePayClient.SubAccountResult result = routePay.createSubAccount(
                    tenantId, businessName, contactEmail);
            account.activate(result.subaccountId());
        } catch (RuntimeException ex) {
            log.error("RoutePay sub-account create failed for tenant {}: {}", tenantId, ex.getMessage());
            account.markProvisioningFailed();
        }
        return tenantAccounts.save(account);
    }

    // ----- payment init -----------------------------------------------------

    @Transactional
    public Payment initPayment(UUID tenantId, String tenantSlug, InitPaymentInput input) {
        if ((input.orderId() == null) == (input.bookingId() == null)) {
            throw new IllegalArgumentException("Exactly one of orderId or bookingId must be set");
        }
        if (input.amountKobo() <= 0) {
            throw new IllegalArgumentException("amount must be positive (in kobo)");
        }
        TenantAccount account = tenantAccounts.findById(tenantId)
                .orElseThrow(() -> new NotFoundException(
                        "No payments account for tenant " + tenantId + " — has it been provisioned?"));
        if (!account.getStatus().canAcceptPayments()) {
            throw new ConflictException(
                    "Tenant payments account is " + account.getStatus() + " — cannot accept payments");
        }

        String reference = "RP-" + tenantSlug + "-" + UUID.randomUUID().toString().substring(0, 8);
        Payment payment = new Payment(tenantId, tenantSlug, input.orderId(), input.bookingId(),
                input.customerId(), input.customerEmail(), input.customerName(),
                input.description(), reference, input.amountKobo());
        payment = payments.save(payment);

        try {
            RoutePayClient.InitPaymentResult result = routePay.initPayment(
                    new RoutePayClient.InitPaymentRequest(
                            reference, input.amountKobo(), input.customerEmail(), input.customerName(),
                            input.description(), input.returnUrl(),
                            account.getRoutepaySubaccountId(), platformFeeBps));
            payment.markInitialised(result.routepayTransactionRef(), result.paymentUrl());
            payment = payments.save(payment);
        } catch (RuntimeException ex) {
            log.error("RoutePay SetRequest failed for reference {}: {}", reference, ex.getMessage());
            payment.markFailed("INIT_FAILED: " + ex.getMessage(), null);
            payments.save(payment);
            throw ex;
        }
        return payment;
    }

    // ----- payment reads -----------------------------------------------------

    @Transactional(readOnly = true)
    public Payment getByReference(UUID tenantId, String reference) {
        Payment p = payments.findByRoutepayReference(reference)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + reference));
        if (!p.getTenantId().equals(tenantId)) {
            throw new NotFoundException("Payment not found: " + reference);
        }
        return p;
    }

    /**
     * Verify: trust the local row when terminal; otherwise refresh from RoutePay
     * (the customer may have just returned from the hosted checkout and we
     * haven't received the webhook yet).
     */
    @Transactional
    public Payment verify(UUID tenantId, String reference) {
        Payment p = getByReference(tenantId, reference);
        if (p.getStatus().isTerminal()) {
            return p;
        }
        try {
            RoutePayClient.GetTransactionResult result = routePay.getTransaction(reference);
            applyStatus(p, result.status(), result.feeKobo(), result.failureReason(), null);
            return payments.save(p);
        } catch (RuntimeException ex) {
            log.warn("RoutePay verify failed for {} — returning PENDING: {}", reference, ex.getMessage());
            return p;
        }
    }

    @Transactional(readOnly = true)
    public Page<Payment> list(UUID tenantId, Pageable pageable) {
        return payments.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    // ----- webhook -----------------------------------------------------------

    /**
     * Webhook entry point. Verifies HMAC signature, dedupes by event id or
     * payload sha, terminalises the payment, fires the conddo-api notify
     * callback. Always returns true on a successful verified-and-deduped run
     * so the controller can 200 RoutePay back regardless.
     */
    @Transactional
    public WebhookResult handleWebhook(byte[] rawBody, String signatureHeader) {
        if (!webhookVerifier.verify(signatureHeader, rawBody)) {
            return WebhookResult.invalidSignature();
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            log.warn("Webhook payload not JSON: {}", ex.getMessage());
            return WebhookResult.malformed();
        }
        String routepayEventId = textOrNull(payload, "eventId");
        String payloadHash = webhookVerifier.sha256Hex(rawBody);

        // Idempotency dedupe — both by event id and by payload hash.
        if (routepayEventId != null
                && webhookEvents.findByRoutepayEventId(routepayEventId).isPresent()) {
            return WebhookResult.duplicate();
        }
        if (webhookEvents.findFirstByPayloadSha256(payloadHash).isPresent()) {
            return WebhookResult.duplicate();
        }

        WebhookEvent event = webhookEvents.save(new WebhookEvent(routepayEventId, signatureHeader, payloadHash));

        // RoutePay's webhook references our local reference; tolerate both common keys.
        String reference = firstNonNull(textOrNull(payload, "reference"),
                textOrNull(payload, "merchantReference"));
        if (reference == null) {
            event.markFailed("Webhook missing reference field");
            return WebhookResult.malformed();
        }

        Optional<Payment> match = payments.findByRoutepayReference(reference);
        if (match.isEmpty()) {
            event.markFailed("Unknown reference: " + reference);
            log.warn("Webhook for unknown reference: {}", reference);
            return WebhookResult.unknownReference();
        }

        Payment payment = match.get();
        if (payment.getStatus().isTerminal()) {
            event.markProcessed(payment.getId());
            return WebhookResult.duplicate();
        }

        String status = textOrNull(payload, "status");
        Long feeKobo = payload.path("fee").isNumber() ? payload.path("fee").asLong() : null;
        String failureReason = textOrNull(payload, "failureReason");
        applyStatus(payment, status, feeKobo, failureReason, payload);
        payments.save(payment);
        event.markProcessed(payment.getId());

        notifyClient.notifyPayment(payment.getTenantId(), payment.getId(),
                payment.getStatus().name(), payment.getOrderId(), payment.getBookingId(),
                payment.getAmountKobo());
        return WebhookResult.processed(payment.getId(), payment.getStatus());
    }

    // ----- helpers -----------------------------------------------------------

    private void applyStatus(Payment payment, String routepayStatus, Long feeKobo,
                             String failureReason, JsonNode webhookPayload) {
        if (routepayStatus == null) {
            return;
        }
        String upper = routepayStatus.trim().toUpperCase();
        switch (upper) {
            case "PAID":
            case "SUCCESS":
            case "SUCCESSFUL":
            case "COMPLETED":
                payment.markPaid(OffsetDateTime.now(), feeKobo, webhookPayload);
                break;
            case "FAILED":
            case "DECLINED":
                payment.markFailed(failureReason == null ? "DECLINED" : failureReason, webhookPayload);
                break;
            case "EXPIRED":
            case "CANCELLED":
                payment.markExpired();
                break;
            default:
                // PENDING or unknown — leave alone, let the next webhook terminalise.
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    // ----- input / output records --------------------------------------------

    public record InitPaymentInput(UUID orderId, UUID bookingId, UUID customerId,
                                   String customerEmail, String customerName,
                                   String description, String returnUrl, long amountKobo) {
    }

    public record WebhookResult(boolean processed, String reason, UUID paymentId, PaymentStatus status) {
        public static WebhookResult invalidSignature() {
            return new WebhookResult(false, "INVALID_SIGNATURE", null, null);
        }

        public static WebhookResult malformed() {
            return new WebhookResult(false, "MALFORMED", null, null);
        }

        public static WebhookResult duplicate() {
            return new WebhookResult(true, "DUPLICATE", null, null);
        }

        public static WebhookResult unknownReference() {
            return new WebhookResult(false, "UNKNOWN_REFERENCE", null, null);
        }

        public static WebhookResult processed(UUID paymentId, PaymentStatus status) {
            return new WebhookResult(true, "PROCESSED", paymentId, status);
        }
    }
}
