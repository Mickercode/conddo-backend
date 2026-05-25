package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STUB {@link EmailSender} — logs instead of sending. Always registered so the
 * platform <b>always has an email channel</b>; {@code ResendEmailSender} (enabled
 * by {@code conddo.notifications.email.provider}) is {@code @Primary} and takes
 * over when configured. Being unconditional means a blank/unknown provider value
 * degrades to logging instead of failing context startup.
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.warn("[email:STUB] -> {} | {} | {}  (configure Resend to send for real)", toEmail, subject, body);
    }
}
