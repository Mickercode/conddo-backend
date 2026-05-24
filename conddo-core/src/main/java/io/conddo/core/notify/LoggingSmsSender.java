package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * STUB {@link SmsSender} — logs the message instead of texting it. The default
 * channel ({@code conddo.notifications.sms.provider} unset or {@code log}), so
 * the OTP flow works for free until {@code TermiiSmsSender} is enabled.
 */
@Component
@ConditionalOnProperty(name = "conddo.notifications.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String toPhone, String message) {
        log.warn("[sms:STUB] -> {} : {}  (wire a real SMS gateway for production)", toPhone, message);
    }
}
