package io.conddo.core.vertical;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.vertical.VerticalConfig.MeasurementField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * In-memory registry of {@link VerticalConfig}s, keyed by vertical id. Backs
 * {@code GET /api/v1/verticals/{id}/config}. Add a vertical by adding an entry.
 */
@Component
public class VerticalConfigRegistry {

    private final Map<String, VerticalConfig> byId = Map.of(
            "fashion", new VerticalConfig(
                    "fashion", "Fashion & Tailoring",
                    List.of("Received", "Measurement Taken", "Fabric Sourced",
                            "In Production", "Ready for Fitting", "Delivered"),
                    List.of(inches("chest", "Chest"), inches("waist", "Waist"), inches("hips", "Hips"),
                            inches("length", "Length"), inches("shoulder", "Shoulder"), inches("sleeve", "Sleeve")),
                    List.of("hero", "about", "services", "gallery", "testimonials", "contact")),
            "pharmacy", new VerticalConfig(
                    "pharmacy", "Pharmacy",
                    List.of("Received", "Processing", "Ready for Pickup", "Dispatched", "Delivered"),
                    List.of(),
                    List.of("hero", "about", "services", "products", "contact")),
            "general", new VerticalConfig(
                    "general", "General Business",
                    List.of("Received", "In Progress", "Ready", "Completed"),
                    List.of(),
                    List.of("hero", "about", "services", "contact")));

    /** The config for a vertical, or null if unknown. */
    public VerticalConfig find(String id) {
        return id == null ? null : byId.get(id.toLowerCase());
    }

    /** The config for a vertical, or 404 if unknown. */
    public VerticalConfig require(String id) {
        VerticalConfig config = find(id);
        if (config == null) {
            throw new NotFoundException("Unknown vertical: " + id);
        }
        return config;
    }

    private static MeasurementField inches(String key, String label) {
        return new MeasurementField(key, label, "in");
    }
}
