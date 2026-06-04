package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Renders the branded HTML email templates (classpath {@code /email-templates/*.html})
 * by substituting {@code {{PLACEHOLDER}}} tokens. {@code {{LOGO_URL}}} is filled
 * from configuration for every template. Returns an empty string if a template
 * is missing, so callers can fall back to plain text.
 *
 * <p>Logo URL precedence: explicit {@code conddo.notifications.email.logo-url}
 * → derived from {@code conddo.app.base-url} (so emails follow the FE host
 * automatically when DNS flips) → fallback {@code app.conddo.io}.
 */
@Component
public class EmailTemplates {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplates.class);
    private static final String FALLBACK_LOGO = "https://app.conddo.io/conddo_logo.png";

    private final String logoUrl;

    public EmailTemplates(NotificationProperties properties,
                          @Value("${conddo.app.base-url:}") String appBaseUrl) {
        String configured = properties.email() != null ? properties.email().logoUrl() : null;
        if (configured != null && !configured.isBlank()) {
            this.logoUrl = configured;
        } else if (appBaseUrl != null && !appBaseUrl.isBlank()) {
            this.logoUrl = stripTrailingSlash(appBaseUrl) + "/conddo_logo.png";
        } else {
            this.logoUrl = FALLBACK_LOGO;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Render {@code <name>.html} with {@code vars} (plus {@code LOGO_URL}). "" if missing. */
    public String render(String name, Map<String, String> vars) {
        String tpl = load(name);
        if (tpl.isBlank()) {
            return "";
        }
        String out = tpl.replace("{{LOGO_URL}}", logoUrl);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private String load(String name) {
        try (InputStream in = getClass().getResourceAsStream("/email-templates/" + name)) {
            if (in == null) {
                log.warn("Email template not found on classpath: {}", name);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.error("Failed to read email template {}: {}", name, ex.getMessage());
            return "";
        }
    }
}
