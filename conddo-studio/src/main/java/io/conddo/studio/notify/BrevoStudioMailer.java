package io.conddo.studio.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Brevo (transactional email) adapter for the Studio's high-priority notifications.
 * Posts to {@code /v3/smtp/email} with the {@code api-key} header — same shape
 * as conddo-api's {@code BrevoEmailSender} so ops only has to learn one provider.
 *
 * <p>Mirrors the §20 AI rule for fail-safe out-of-band integrations: any
 * transport failure is logged at {@code ERROR} but never propagates, so the
 * lifecycle flow that triggered the email (job submit / return / escalate) is
 * unaffected. Wired only when {@code studio.notifications.email.provider=brevo}.
 */
@Component
@Primary
@ConditionalOnProperty(name = "studio.notifications.email.provider", havingValue = "brevo")
public class BrevoStudioMailer implements StudioMailer {

    private static final Logger log = LoggerFactory.getLogger(BrevoStudioMailer.class);
    private static final String DEFAULT_BASE_URL = "https://api.brevo.com";

    private final RestClient restClient;
    private final String fromEmail;
    private final String fromName;
    private final boolean configured;

    public BrevoStudioMailer(@Value("${studio.notifications.email.api-key:}") String apiKey,
                             @Value("${studio.notifications.email.from:}") String fromEmail,
                             @Value("${studio.notifications.email.from-name:Conddo Studio}") String fromName,
                             @Value("${studio.notifications.email.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                             RestClient.Builder builder) {
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.configured = apiKey != null && !apiKey.isBlank() && fromEmail != null && !fromEmail.isBlank();
        this.restClient = builder
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl)
                .defaultHeader("api-key", apiKey == null ? "" : apiKey)
                .build();
        if (!configured) {
            log.warn("Brevo Studio mailer selected but api-key/from is blank — emails will be dropped");
        }
    }

    @Override
    public void send(String toEmail, String subject, String htmlBody, String textBody) {
        if (!configured) {
            return;   // keep the lifecycle flowing; in-app notification + SSE still fire
        }
        if (toEmail == null || toEmail.isBlank()) {
            return;
        }
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
            // Lifecycle flows must never break because of email.
            log.error("Brevo Studio email to {} failed: {}", toEmail, ex.getMessage());
        }
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }
}
