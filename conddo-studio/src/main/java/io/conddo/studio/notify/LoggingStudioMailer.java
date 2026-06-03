package io.conddo.studio.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default no-op mailer — logs the message and returns. Lets the Studio boot
 * without any email provider configured (in dev, on Render before secrets are
 * set) while still surfacing what <i>would</i> have been sent in the application
 * log. {@link BrevoStudioMailer} replaces it as {@code @Primary} when
 * {@code studio.notifications.email.provider=brevo}.
 */
@Component
public class LoggingStudioMailer implements StudioMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingStudioMailer.class);

    @Override
    public void send(String toEmail, String subject, String htmlBody, String textBody) {
        log.info("[studio-email/dormant] to={} subject={} text={}", toEmail, subject,
                textBody == null ? "" : textBody.replace('\n', ' '));
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
