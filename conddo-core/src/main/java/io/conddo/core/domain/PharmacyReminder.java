package io.conddo.core.domain;

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
 * Scheduled customer SMS reminder (Pharmacy Spec v2 §12D). Created by
 * the pharmacist, picked up by an hourly scheduler, interpolated with
 * customer + product context, and dispatched through Brevo
 * ({@code SmsSender}). Recurring reminders insert a NEW SCHEDULED row
 * for the next occurrence on each send so the audit trail stays
 * append-only.
 */
@Entity
@Table(name = "pharmacy_reminders")
public class PharmacyReminder {

    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String RECURRENCE_ONCE = "ONCE";
    public static final String RECURRENCE_DAILY = "DAILY";
    public static final String RECURRENCE_WEEKLY = "WEEKLY";
    public static final String RECURRENCE_MONTHLY = "MONTHLY";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "reminder_type", nullable = false, length = 30)
    private String reminderType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(length = 20)
    private String recurrence;

    @Column(name = "recurrence_end")
    private OffsetDateTime recurrenceEnd;

    @Column(nullable = false, length = 20)
    private String status = STATUS_SCHEDULED;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PharmacyReminder() {
    }

    public PharmacyReminder(UUID tenantId, UUID customerId, UUID productId, String reminderType,
                            String message, OffsetDateTime scheduledAt, String recurrence,
                            OffsetDateTime recurrenceEnd, UUID createdBy) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.productId = productId;
        this.reminderType = reminderType;
        this.message = message;
        this.scheduledAt = scheduledAt;
        this.recurrence = recurrence;
        this.recurrenceEnd = recurrenceEnd;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getReminderType() {
        return reminderType;
    }

    public String getMessage() {
        return message;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public String getRecurrence() {
        return recurrence;
    }

    public OffsetDateTime getRecurrenceEnd() {
        return recurrenceEnd;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void markSent(OffsetDateTime at) {
        this.status = STATUS_SENT;
        this.sentAt = at;
        this.failureReason = null;
    }

    public void markFailed(OffsetDateTime at, String reason) {
        this.status = STATUS_FAILED;
        this.sentAt = at;
        this.failureReason = reason;
    }

    public void markCancelled() {
        this.status = STATUS_CANCELLED;
    }
}
