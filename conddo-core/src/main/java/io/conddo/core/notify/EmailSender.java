package io.conddo.core.notify;

/**
 * Outbound email channel (PRD §6.4). Default implementation is a logging stub;
 * {@code ResendEmailSender} is the real adapter, enabled by configuration.
 */
public interface EmailSender {

    void send(String toEmail, String subject, String body);

    /**
     * Send a branded HTML email with a plain-text fallback. Defaults to the
     * plain-text {@link #send} path so the stub/legacy senders keep working;
     * real adapters (Brevo/Resend) override to deliver the HTML body.
     */
    default void sendHtml(String toEmail, String subject, String htmlBody, String textBody) {
        send(toEmail, subject, textBody != null ? textBody : htmlBody);
    }
}
