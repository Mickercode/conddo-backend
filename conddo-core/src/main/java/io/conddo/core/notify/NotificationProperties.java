package io.conddo.core.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notification channel configuration (PRD §6.4). Each channel has a
 * {@code provider} ({@code log} = stub, the default) plus provider credentials.
 * Bound from {@code conddo.notifications.*}.
 */
@ConfigurationProperties(prefix = "conddo.notifications")
public record NotificationProperties(
        Sms sms,
        Email email
) {

    /** SMS via Termii (Nigerian gateway). */
    public record Sms(String provider, String baseUrl, String apiKey, String senderId) {
    }

    /** Email via Brevo (transactional) or Resend. {@code from} is the verified
     *  sender address; {@code fromName} the display name; {@code logoUrl} the
     *  public logo URL embedded in the branded HTML templates;
     *  {@code templates} carries Brevo template ids per transactional kind. */
    public record Email(String provider, String baseUrl, String apiKey, String from, String fromName,
                        String logoUrl, Templates templates) {
    }

    /**
     * Brevo template ids per transactional kind. Each id is optional; when null
     * the caller falls back to the inline plain-text body so dev/test still
     * works without Brevo configured.
     */
    public record Templates(Long invite) {
    }
}
