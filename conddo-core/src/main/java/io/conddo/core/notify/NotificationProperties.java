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

    /** Email via Resend. */
    public record Email(String provider, String baseUrl, String apiKey, String from) {
    }
}
