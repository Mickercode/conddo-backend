package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Real email channel via Resend. Enabled only when
 * {@code conddo.notifications.email.provider=resend}; otherwise
 * {@link LoggingEmailSender} is used. Posts to Resend's {@code /emails} endpoint.
 *
 * <p><b>Unverified against the live API</b> — built from Resend's documented
 * contract; confirm the verified {@code from} domain when you enable it.
 */
@Component
@ConditionalOnProperty(name = "conddo.notifications.email.provider", havingValue = "resend")
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final String DEFAULT_BASE_URL = "https://api.resend.com";

    private final RestClient restClient;
    private final String from;

    public ResendEmailSender(NotificationProperties properties, RestClient.Builder restClientBuilder) {
        NotificationProperties.Email email = properties.email();
        String baseUrl = email.baseUrl() != null ? email.baseUrl() : DEFAULT_BASE_URL;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + email.apiKey())
                .build();
        this.from = email.from();
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        try {
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", from,
                            "to", List.of(toEmail),
                            "subject", subject,
                            "text", body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.error("Resend email to {} failed: {}", toEmail, ex.getMessage());
        }
    }
}
