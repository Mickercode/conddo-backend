package io.conddo.api.web.dto;

import java.util.Map;

/**
 * PUT availability settings (§11.5). {@code workingHours} maps weekday keys
 * (mon..sun) to {@code {open, start, end}}. All optional — only sent fields change.
 */
public record AvailabilityRequest(
        Map<String, Object> workingHours,
        Integer slotDurationMinutes,
        Integer bufferMinutes
) {
}
