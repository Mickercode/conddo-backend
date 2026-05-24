package io.conddo.core.vertical;

import java.util.List;

/**
 * Reference configuration for a business vertical (Fashion, Pharmacy, …): the
 * default order-pipeline stages, the measurement fields a customer profile
 * collects, and the website section types. Drives the vertical-aware dashboard
 * (§11.3–11.5). Static reference data for now — predefined by Conddo, not tenant
 * editable; could move to a config table later.
 */
public record VerticalConfig(
        String id,
        String name,
        List<String> orderStages,
        List<MeasurementField> measurementFields,
        List<String> websiteSections
) {

    /** A measurement a vertical tracks on a customer/order (e.g. chest, waist). */
    public record MeasurementField(String key, String label, String unit) {
    }
}
