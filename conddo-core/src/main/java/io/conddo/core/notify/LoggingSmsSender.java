package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STUB {@link SmsSender} — logs the message instead of texting it. Always
 * registered so the platform <b>always has an SMS channel</b>; a real provider
 * ({@code BrevoSmsSender}/{@code TermiiSmsSender}, enabled by
 * {@code conddo.notifications.sms.provider}) is {@code @Primary} and takes over
 * when configured. Being unconditional means a blank/unknown provider value
 * degrades to logging instead of failing context startup.
 */
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String toPhone, String message) {
        log.warn("[sms:STUB] -> {} : {}  (wire a real SMS gateway for production)", toPhone, message);
    }
}
