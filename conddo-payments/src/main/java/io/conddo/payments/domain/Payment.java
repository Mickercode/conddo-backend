package io.conddo.payments.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One payment attempt. The flow:
 * <ol>
 *   <li>{@code init} — caller asks for a payment intent; we generate a local
 *       reference, ask RoutePay to set up a hosted checkout, persist the row,
 *       return {@code paymentUrl} for the customer to visit.</li>
 *   <li>Customer pays via RoutePay's hosted page.</li>
 *   <li>RoutePay webhook hits us → we transition status to {@code PAID} /
 *       {@code FAILED} and notify conddo-api so the order/booking flips.</li>
 *   <li>FE polls {@link io.conddo.payments.web.PaymentsController#verify} for
 *       the customer's return-from-checkout flow.</li>
 * </ol>
 *
 * <p>Terminal statuses are never reverted — once {@code PAID}, a re-posted
 * webhook is a no-op.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "tenant_slug", nullable = false)
    private String tenantSlug;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column
    private String description;

    @Column(name = "routepay_reference", nullable = false, unique = true)
    private String routepayReference;

    @Column(name = "routepay_transaction_ref", unique = true)
    private String routepayTransactionRef;

    @Column(name = "payment_url")
    private String paymentUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "amount_kobo", nullable = false)
    private long amountKobo;

    @Column(nullable = false)
    private String currency = "NGN";

    @Column(name = "fee_kobo")
    private Long feeKobo;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_webhook_payload")
    private JsonNode rawWebhookPayload;

    protected Payment() {
    }

    public Payment(UUID tenantId, String tenantSlug, UUID orderId, UUID bookingId,
                   UUID customerId, String customerEmail, String customerName, String description,
                   String routepayReference, long amountKobo) {
        this.tenantId = tenantId;
        this.tenantSlug = tenantSlug;
        this.orderId = orderId;
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.customerName = customerName;
        this.description = description;
        this.routepayReference = routepayReference;
        this.amountKobo = amountKobo;
    }

    public void markInitialised(String routepayTransactionRef, String paymentUrl) {
        this.routepayTransactionRef = routepayTransactionRef;
        this.paymentUrl = paymentUrl;
    }

    public void markPaid(OffsetDateTime paidAt, Long feeKobo, JsonNode webhookPayload) {
        if (status.isTerminal()) {
            return;
        }
        this.status = PaymentStatus.PAID;
        this.paidAt = paidAt;
        this.feeKobo = feeKobo;
        this.rawWebhookPayload = webhookPayload;
    }

    public void markFailed(String reason, JsonNode webhookPayload) {
        if (status.isTerminal()) {
            return;
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.rawWebhookPayload = webhookPayload;
    }

    public void markExpired() {
        if (status.isTerminal()) {
            return;
        }
        this.status = PaymentStatus.EXPIRED;
    }

    // ----- getters ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTenantSlug() {
        return tenantSlug;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getDescription() {
        return description;
    }

    public String getRoutepayReference() {
        return routepayReference;
    }

    public String getRoutepayTransactionRef() {
        return routepayTransactionRef;
    }

    public String getPaymentUrl() {
        return paymentUrl;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public long getAmountKobo() {
        return amountKobo;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getFeeKobo() {
        return feeKobo;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public JsonNode getRawWebhookPayload() {
        return rawWebhookPayload;
    }
}
