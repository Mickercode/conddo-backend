package io.conddo.core.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The central notifications engine (PRD §6.4): the single entry point modules
 * use to notify users. It owns message content and routes to the right channel
 * ({@link SmsSender} / {@link EmailSender}), each of which is a stub or a real
 * provider depending on configuration. Emails render the branded HTML templates
 * ({@link EmailTemplates}) with a plain-text fallback.
 *
 * <p>Delivery is synchronous for now; when the Redis event bus lands (item 6)
 * this becomes the place that consumes notification events asynchronously,
 * without changing callers.
 */
@Service
public class NotificationService {

    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final EmailTemplates templates;
    private final String appBaseUrl;
    private final String otpExpiryMinutes;

    public NotificationService(SmsSender smsSender, EmailSender emailSender, EmailTemplates templates,
                               @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl,
                               @Value("${conddo.security.otp.ttl:10m}") String otpTtl) {
        this.smsSender = smsSender;
        this.emailSender = emailSender;
        this.templates = templates;
        this.appBaseUrl = appBaseUrl;
        this.otpExpiryMinutes = otpTtl.replaceAll("[^0-9]", "").isBlank() ? "10" : otpTtl.replaceAll("[^0-9]", "");
    }

    /** Signup verification code by SMS (needs a funded SMS provider, e.g. Brevo credits). */
    public void sendOtp(String phone, String code) {
        smsSender.send(phone, "Your Conddo verification code is " + code);
    }

    /** Signup verification code by email — the branded template with a text fallback. */
    public void sendOtpEmail(String toEmail, String code) {
        String subject = "Your Conddo verification code";
        String text = "Your Conddo verification code is " + code + ". It expires in "
                + otpExpiryMinutes + " minutes. If you didn't request this, you can ignore this email.";
        String html = templates.render("verification-code.html",
                Map.of("CODE", code, "EXPIRY_MINUTES", otpExpiryMinutes));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }

    /**
     * Alerts a merchant that a new order landed on their public website
     * (WEBSITE_INTEGRATION_SPEC §3 / merchant-readiness slice 2). Both
     * channels are best-effort and independent — one provider failing
     * never blocks the other, and never bubbles to the caller (the
     * checkout response has already gone back to the customer).
     */
    public void sendOrderAlert(String toEmail, String toPhone, String businessName,
                               String customerName, String orderReference, String totalNgn) {
        String subject = "New order on your conddo.io site";
        String text = "Hi " + nullSafe(businessName) + ",\n\n"
                + nullSafe(customerName) + " just placed order " + nullSafe(orderReference)
                + " for ₦" + nullSafe(totalNgn) + " on your conddo.io website.\n\n"
                + "View it on your dashboard: " + appBaseUrl + "/orders/" + nullSafe(orderReference) + "\n\n"
                + "— Conddo";
        if (toEmail != null && !toEmail.isBlank()) {
            try {
                emailSender.send(toEmail, subject, text);
            } catch (RuntimeException ignored) {
                // Provider blip — SMS is the fallback channel; swallow.
            }
        }
        if (toPhone != null && !toPhone.isBlank()) {
            String sms = "New conddo.io order " + nullSafe(orderReference)
                    + " — ₦" + nullSafe(totalNgn) + " from " + nullSafe(customerName);
            try {
                smsSender.send(toPhone, sms);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Booking parity to {@link #sendOrderAlert} — fired by the
     * BookingNotificationListener when a customer self-books on the
     * merchant's public booking link. Both channels best-effort.
     */
    public void sendBookingAlert(String toEmail, String toPhone, String businessName,
                                 String customerName, String service, String when,
                                 String contactPhone) {
        String subject = "New booking request on your conddo.io site";
        String text = "Hi " + nullSafe(businessName) + ",\n\n"
                + nullSafe(customerName) + " just requested a booking"
                + (service == null || service.isBlank() ? "" : " for " + service)
                + (when == null || when.isBlank() ? "" : " at " + when)
                + (contactPhone == null || contactPhone.isBlank() ? "" : " (contact: " + contactPhone + ")")
                + ".\n\nReview it on your dashboard: " + appBaseUrl + "/bookings\n\n"
                + "— Conddo";
        if (toEmail != null && !toEmail.isBlank()) {
            try {
                emailSender.send(toEmail, subject, text);
            } catch (RuntimeException ignored) {
            }
        }
        if (toPhone != null && !toPhone.isBlank()) {
            String sms = "New booking on conddo.io — " + nullSafe(customerName)
                    + (service == null || service.isBlank() ? "" : " — " + service)
                    + (when == null || when.isBlank() ? "" : " — " + when);
            try {
                smsSender.send(toPhone, sms);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Alerts a merchant when their subscription transitions
     * (BILLING_TIERS_SPEC §6). Two transitions are merchant-visible:
     * {@code trialing/active → grace} (kindly: "your trial ended, here's a
     * grace window to add payment") and {@code grace → expired} (sharper:
     * "your access is paused; reactivate to resume"). Both channels
     * best-effort; the cron has already committed the state change.
     */
    public void sendPlanTransition(String toEmail, String toPhone, String businessName,
                                   String planName, String toStatus, int gracePeriodDays) {
        if ((toEmail == null || toEmail.isBlank()) && (toPhone == null || toPhone.isBlank())) {
            return;
        }
        String subject;
        String text;
        String sms;
        String biz = nullSafe(businessName);
        String plan = nullSafe(planName);
        switch (toStatus) {
            case "grace" -> {
                subject = "Your conddo.io trial just ended";
                text = "Hi " + biz + ",\n\n"
                        + "Your " + plan + " trial just ended. You're in a "
                        + gracePeriodDays + "-day grace period — add a payment method "
                        + "to keep your conddo.io features running.\n\n"
                        + "Add payment: " + appBaseUrl + "/settings/billing\n\n"
                        + "— Conddo";
                sms = "Your conddo.io " + plan + " trial ended. " + gracePeriodDays
                        + "-day grace period — add payment at " + appBaseUrl + "/settings/billing";
            }
            case "expired" -> {
                subject = "Your conddo.io subscription has expired";
                text = "Hi " + biz + ",\n\n"
                        + "Your " + plan + " subscription has expired and your conddo.io "
                        + "site is paused. Reactivate to restore access to your customers.\n\n"
                        + "Reactivate: " + appBaseUrl + "/settings/billing\n\n"
                        + "— Conddo";
                sms = "Your conddo.io " + plan + " subscription expired. Reactivate at "
                        + appBaseUrl + "/settings/billing";
            }
            default -> {
                // Other states (cancelled-completion etc.) stay silent.
                return;
            }
        }
        if (toEmail != null && !toEmail.isBlank()) {
            try {
                emailSender.send(toEmail, subject, text);
            } catch (RuntimeException ignored) {
            }
        }
        if (toPhone != null && !toPhone.isBlank()) {
            try {
                smsSender.send(toPhone, sms);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Password reset — delivers the reset token (and a reset link) by email. */
    public void sendPasswordReset(String toEmail, String resetToken) {
        String subject = "Reset your Conddo password";
        String resetUrl = appBaseUrl + "/reset-password?token=" + resetToken;
        String text = "Reset your Conddo password with this link: " + resetUrl
                + "\n\nOr paste this code on the reset page: " + resetToken
                + "\n\nIf you didn't request this, you can safely ignore this email.";
        String html = templates.render("password-reset.html",
                Map.of("RESET_URL", resetUrl, "RESET_TOKEN", resetToken, "EXPIRY_MINUTES", "60"));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }
}
