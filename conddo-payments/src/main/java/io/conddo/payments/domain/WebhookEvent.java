package io.conddo.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Idempotency log for RoutePay webhooks. We dedupe on the event id when
 * present, else on the SHA-256 of the payload — so the same webhook re-posted
 * gets short-circuited without re-applying the state change.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "routepay_event_id", unique = true)
    private String routepayEventId;

    @Column
    private String signature;

    @Column(name = "payload_sha256", nullable = false)
    private String payloadSha256;

    @Column(name = "payment_id")
    private UUID paymentId;

    @CreationTimestamp
    @Column(name = "received_at", updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column
    private String error;

    protected WebhookEvent() {
    }

    public WebhookEvent(String routepayEventId, String signature, String payloadSha256) {
        this.routepayEventId = routepayEventId;
        this.signature = signature;
        this.payloadSha256 = payloadSha256;
    }

    public void markProcessed(UUID paymentId) {
        this.paymentId = paymentId;
        this.processedAt = OffsetDateTime.now();
    }

    public void markFailed(String error) {
        this.error = error;
        this.processedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getRoutepayEventId() {
        return routepayEventId;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }
}
