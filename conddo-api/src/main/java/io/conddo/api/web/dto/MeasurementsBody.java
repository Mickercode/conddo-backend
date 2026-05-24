package io.conddo.api.web.dto;

import java.util.Map;

/**
 * GET/PUT body for a customer's measurement profile (§11.3). Keys are the
 * vertical's measurement fields (from {@code /verticals/{id}/config}); values
 * are free-form (numbers/strings), so this is an open map.
 */
public record MeasurementsBody(Map<String, Object> measurements) {
}
