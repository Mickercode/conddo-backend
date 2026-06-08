package io.conddo.studio.web.dto;

/**
 * POST /admin/platform/sites/{id}/qa-approve (or /qa-revoke) — optional
 * note that goes into the audit log entry.
 */
public record QaActionRequest(String note) {
}
