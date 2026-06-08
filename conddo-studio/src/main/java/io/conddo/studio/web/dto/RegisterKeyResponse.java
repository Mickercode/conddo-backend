package io.conddo.studio.web.dto;

/**
 * Returned exactly once on POST /admin/platform/sites and POST
 * /admin/platform/sites/{id}/rotate-key. {@code apiKey} is the plaintext
 * value — the FE displays it in the one-time modal and never sees it
 * again after the page closes.
 */
public record RegisterKeyResponse(PlatformSiteDto site, String apiKey) {
}
