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
 * An in-progress signup (PRD §6.2): the person's account details plus the
 * current hashed OTP, held until the phone is verified and the wizard completes
 * (which creates the tenant + admin user). Pre-tenant, so not RLS-scoped — see
 * V7__pending_registrations.sql. The OTP policy (limits/expiry) lives in the
 * registration service; this entity just holds the state and exposes guards.
 */
@Entity
@Table(name = "pending_registrations")
public class PendingRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "otp_expires_at", nullable = false)
    private OffsetDateTime otpExpiresAt;

    @Column(name = "otp_attempts", nullable = false)
    private int otpAttempts = 0;

    @Column(name = "otp_sent_count", nullable = false)
    private int otpSentCount = 1;

    @Column(name = "last_otp_sent_at", nullable = false)
    private OffsetDateTime lastOtpSentAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PendingRegistration() {
    }

    public PendingRegistration(String fullName, String phone, String email, String passwordHash,
                               String otpHash, OffsetDateTime otpExpiresAt, OffsetDateTime sentAt) {
        this.fullName = fullName;
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.otpHash = otpHash;
        this.otpExpiresAt = otpExpiresAt;
        this.lastOtpSentAt = sentAt;
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public boolean isOtpExpired(OffsetDateTime now) {
        return !otpExpiresAt.isAfter(now);
    }

    public boolean attemptsExhausted(int maxAttempts) {
        return otpAttempts >= maxAttempts;
    }

    public boolean resendsExhausted(int maxResends) {
        return otpSentCount >= maxResends;
    }

    /** True once the resend cooldown has elapsed since the last send. */
    public boolean canResend(OffsetDateTime now, java.time.Duration cooldown) {
        return !lastOtpSentAt.plus(cooldown).isAfter(now);
    }

    public void recordFailedAttempt() {
        this.otpAttempts++;
    }

    /** Replaces the code on a resend: new hash/expiry, reset attempts, bump the send count. */
    public void newCode(String otpHash, OffsetDateTime expiresAt, OffsetDateTime sentAt) {
        this.otpHash = otpHash;
        this.otpExpiresAt = expiresAt;
        this.otpAttempts = 0;
        this.otpSentCount++;
        this.lastOtpSentAt = sentAt;
    }

    public void markPhoneVerified() {
        this.phoneVerified = true;
    }

    public void markCompleted(OffsetDateTime at) {
        this.completedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public OffsetDateTime getOtpExpiresAt() {
        return otpExpiresAt;
    }

    public int getOtpAttempts() {
        return otpAttempts;
    }

    public int getOtpSentCount() {
        return otpSentCount;
    }

    public OffsetDateTime getLastOtpSentAt() {
        return lastOtpSentAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
