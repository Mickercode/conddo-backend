package io.conddo.studio.notify;

/**
 * Studio outbound email port. Used for high-priority lifecycle alerts that staff
 * may miss in the in-app feed — revision requests, reassignments, and SLA
 * escalations to team leads.
 *
 * <p>Implementations <b>never throw</b>: a misconfigured provider, a transport
 * failure, or a missing recipient are logged and silently dropped, so the calling
 * lifecycle flow (job submit, QA return, escalate) is never broken by email.
 */
public interface StudioMailer {

    /** Send one transactional email. {@code htmlBody} is optional. */
    void send(String toEmail, String subject, String htmlBody, String textBody);

    /** Whether a real provider (Brevo) is wired — used for /actuator-style introspection. */
    boolean isConfigured();
}
