package io.conddo.core.notify;

/**
 * Per-tenant email branding (V52). {@code fromName} replaces the
 * default "Conddo" sender display name; {@code replyTo} sets the
 * Reply-To header so customer replies go to the tenant's contact
 * email. Either may be null when the tenant hasn't customised
 * (falls back to business name / contact email).
 */
public record TenantEmailBranding(String fromName, String replyTo) {
}
