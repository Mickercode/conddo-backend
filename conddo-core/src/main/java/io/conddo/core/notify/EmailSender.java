package io.conddo.core.notify;

import java.util.Map;

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

    /**
     * Send a provider-templated email. The provider holds the subject + body
     * markup; we pass the variables. Real adapters (Brevo) override to call
     * the templated endpoint; defaults to the {@link #send} fallback so the
     * stub/dev sender still produces something readable when a template id
     * isn't configured.
     */
    default void sendTemplate(String toEmail, long templateId, Map<String, Object> params,
                              String fallbackSubject, String fallbackBody) {
        send(toEmail, fallbackSubject, fallbackBody);
    }
}
