package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STUB {@link SmsSender} — logs the message instead of texting it. Genuinely
 * free and lets the frontend exercise the whole OTP flow now; replace with a
 * real gateway adapter (Termii/Africa's Talking/Twilio) when going live.
 */
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String toPhone, String message) {
        log.warn("[sms:STUB] -> {} : {}  (wire a real SMS gateway for production)", toPhone, message);
    }
}
