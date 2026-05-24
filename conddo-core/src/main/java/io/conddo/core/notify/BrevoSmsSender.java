package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real SMS channel via Brevo (transactional SMS). Enabled only when
 * {@code conddo.notifications.sms.provider=brevo}; otherwise {@link LoggingSmsSender}
 * (default) or {@link TermiiSmsSender} is used. POSTs to Brevo's
 * {@code /v3/transactionalSMS/sms} with the {@code api-key} header.
 *
 * <p><b>Note:</b> Brevo's free tier does not include SMS credits — transactional
 * SMS requires purchased credits and a registered sender name (≤11 alphanumeric
 * chars). Delivery failures are logged and never break the calling flow (e.g. signup).
 */
@Component
@ConditionalOnProperty(name = "conddo.notifications.sms.provider", havingValue = "brevo")
public class BrevoSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoSmsSender.class);
    private static final String DEFAULT_BASE_URL = "https://api.brevo.com";

    private final RestClient restClient;
    private final String apiKey;
    private final String senderId;

    public BrevoSmsSender(NotificationProperties properties) {
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
                    .uri("/v3/transactionalSMS/sms")
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "type", "transactional",
                            "sender", senderId,
                            "recipient", toPhone,
                            "content", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            // Delivery failures must not break the calling flow (e.g. signup).
            log.error("Brevo SMS to {} failed: {}", toPhone, ex.getMessage());
        }
    }
}
