package io.conddo.core.notify;

import org.springframework.stereotype.Service;

/**
 * The central notifications engine (PRD §6.4): the single entry point modules
 * use to notify users. It owns message content and routes to the right channel
 * ({@link SmsSender} / {@link EmailSender}), each of which is a stub or a real
 * provider depending on configuration.
 *
 * <p>Delivery is synchronous for now; when the Redis event bus lands (item 6)
 * this becomes the place that consumes notification events asynchronously,
 * without changing callers.
 */
@Service
public class NotificationService {

    private final SmsSender smsSender;
    private final EmailSender emailSender;

    public NotificationService(SmsSender smsSender, EmailSender emailSender) {
        this.smsSender = smsSender;
        this.emailSender = emailSender;
    }

    /** Signup verification code by SMS (needs a funded SMS provider, e.g. Brevo credits). */
    public void sendOtp(String phone, String code) {
        smsSender.send(phone, "Your Conddo verification code is " + code);
    }

    /** Signup verification code by email — the free path (Resend), no SMS credits needed. */
    public void sendOtpEmail(String toEmail, String code) {
        emailSender.send(toEmail, "Your Conddo verification code",
                "Your Conddo verification code is " + code + ".\n\n"
                        + "It expires shortly. If you didn't request this, you can ignore this email.");
    }

    /** Password reset — delivers the reset token by email. */
    public void sendPasswordReset(String toEmail, String resetToken) {
        String body = "We received a request to reset your Conddo password.\n\n"
                + "Reset token: " + resetToken + "\n\n"
                + "If you didn't request this, you can safely ignore this email.";
        emailSender.send(toEmail, "Reset your Conddo password", body);
    }
}
