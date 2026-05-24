package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real SMS channel via Termii (Nigerian gateway). Enabled only when
 * {@code conddo.notifications.sms.provider=termii}; otherwise {@link LoggingSmsSender}
 * is used. Posts to Termii's {@code /api/sms/send} per their public API.
 *
 * <p><b>Unverified against the live API</b> — built from Termii's documented
 * contract; confirm sender-id/credentials when you enable it.
 */
@Component
@ConditionalOnProperty(name = "conddo.notifications.sms.provider", havingValue = "termii")
public class TermiiSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(TermiiSmsSender.class);
    private static final String DEFAULT_BASE_URL = "https://api.ng.termii.com";

    private final RestClient restClient;
    private final String apiKey;
    private final String senderId;

    public TermiiSmsSender(NotificationProperties properties) {
        NotificationProperties.Sms sms = properties.sms();
        String baseUrl = sms.baseUrl() != null ? sms.baseUrl() : DEFAULT_BASE_URL;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = sms.apiKey();
        this.senderId = sms.senderId();
    }

    @Override
    public void send(String toPhone, String message) {
        try {
            restClient.post()
                    .uri("/api/sms/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "to", toPhone,
                            "from", senderId,
                            "sms", message,
                            "type", "plain",
                            "channel", "generic",
                            "api_key", apiKey))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            // Delivery failures must not break the calling flow (e.g. signup).
            log.error("Termii SMS to {} failed: {}", toPhone, ex.getMessage());
        }
    }
}
