package io.conddo.api.web.dto;

import io.conddo.core.vertical.VerticalConfig;

import java.util.List;

/**
 * Vertical reference config (§11.12 {@code GET /api/v1/verticals/{id}/config}) —
 * drives vertical-aware screens (order stages, measurement labels, sections).
 */
public record VerticalConfigResponse(
        String id,
        String name,
        List<String> orderStages,
        List<MeasurementField> measurementFields,
        List<String> websiteSections
) {

    public record MeasurementField(String key, String label, String unit) {
    }

    public static VerticalConfigResponse from(VerticalConfig config) {
        List<MeasurementField> fields = config.measurementFields().stream()
                .map(f -> new MeasurementField(f.key(), f.label(), f.unit()))
                .toList();
        return new VerticalConfigResponse(
                config.id(), config.name(), config.orderStages(), fields, config.websiteSections());
    }
}
