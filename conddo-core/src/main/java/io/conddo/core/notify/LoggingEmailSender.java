package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * STUB {@link EmailSender} — logs instead of sending. The default channel
 * ({@code conddo.notifications.email.provider} unset or {@code log}), so the
 * platform runs with no email provider until {@code ResendEmailSender} is
 * enabled.
 */
@Component
@ConditionalOnProperty(name = "conddo.notifications.email.provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.warn("[email:STUB] -> {} | {} | {}  (configure Resend to send for real)", toEmail, subject, body);
    }
}
