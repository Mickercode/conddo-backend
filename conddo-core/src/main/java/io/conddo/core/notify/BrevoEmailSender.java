package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real email channel via Brevo (transactional email). Enabled only when
 * {@code conddo.notifications.email.provider=brevo}; otherwise
 * {@link ResendEmailSender} (resend) or {@link LoggingEmailSender} (default) is
 * used. Posts to Brevo's {@code /v3/smtp/email} with the {@code api-key} header.
 *
 * <p>Chosen over Resend because Brevo's free tier delivers to <b>any</b>
 * recipient (300/day) without a verified domain — only a verified sender address
 * is required (set {@code conddo.notifications.email.from}). Delivery failures
 * are logged and never break the calling flow (e.g. signup).
 */
@Component
@Primary
@ConditionalOnProperty(name = "conddo.notifications.email.provider", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);
    private static final String DEFAULT_BASE_URL = "https://api.brevo.com";

    private final RestClient restClient;
    private final String fromEmail;
    private final String fromName;

    public BrevoEmailSender(NotificationProperties properties, RestClient.Builder restClientBuilder) {
        NotificationProperties.Email email = properties.email();
        String baseUrl = email.baseUrl() != null && !email.baseUrl().isBlank() ? email.baseUrl() : DEFAULT_BASE_URL;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("api-key", email.apiKey())
                .build();
        this.fromEmail = email.from();
        this.fromName = email.fromName() != null && !email.fromName().isBlank() ? email.fromName() : "Conddo";
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        sendHtml(toEmail, subject, null, body);
    }

    @Override
    public void sendHtml(String toEmail, String subject, String htmlBody, String textBody) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sender", Map.of("name", fromName, "email", fromEmail));
            payload.put("to", List.of(Map.of("email", toEmail)));
            payload.put("subject", subject);
            if (htmlBody != null) {
                payload.put("htmlContent", htmlBody);
            }
            if (textBody != null) {
                payload.put("textContent", textBody);
            }
            restClient.post()
                    .uri("/v3/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            // Delivery failures must not break the calling flow (e.g. signup).
            log.error("Brevo email to {} failed: {}", toEmail, ex.getMessage());
        }
    }
}
