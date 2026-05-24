package io.conddo.core.notify;

/**
 * Outbound email channel (PRD §6.4). Default implementation is a logging stub;
 * {@code ResendEmailSender} is the real adapter, enabled by configuration.
 */
public interface EmailSender {

    void send(String toEmail, String subject, String body);
}
