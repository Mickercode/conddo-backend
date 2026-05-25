package io.conddo.core.vertical;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.vertical.VerticalConfig.MeasurementField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * In-memory registry of {@link VerticalConfig}s, keyed by vertical id. Backs
 * {@code GET /api/v1/verticals/{id}/config} with the reference order stages,
 * measurement fields, and website sections a vertical's dashboard uses.
 *
 * <p>Covers the seven canonical verticals of {@code VERTICALS.md} plus a
 * {@code general}/{@code default} fallback (these two share one config — the
 * matrix's {@code DEFAULT_VERTICAL}). Ids match the {@code vertical} claim and
 * {@link io.conddo.core.registry.VerticalToolMatrix} keys. Add a vertical by
 * adding an entry; an unknown id is a 404 (the config is not auto-defaulted).
 */
@Component
public class VerticalConfigRegistry {

    private final Map<String, VerticalConfig> byId;

    public VerticalConfigRegistry() {
        VerticalConfig general = new VerticalConfig(
                "general", "General Business",
                List.of("Received", "In Progress", "Ready", "Completed"),
                List.of(),
                List.of("hero", "about", "services", "contact"));

        this.byId = Map.ofEntries(
                Map.entry("fashion", new VerticalConfig(
                        "fashion", "Fashion & Tailoring",
                        List.of("Received", "Measurement Taken", "Fabric Sourced",
                                "In Production", "Ready for Fitting", "Delivered"),
                        List.of(inches("chest", "Chest"), inches("waist", "Waist"), inches("hips", "Hips"),
                                inches("length", "Length"), inches("shoulder", "Shoulder"), inches("sleeve", "Sleeve")),
                        List.of("hero", "about", "services", "gallery", "testimonials", "contact"))),
                Map.entry("pharmacy", new VerticalConfig(
                        "pharmacy", "Pharmacy",
                        List.of("Received", "Processing", "Ready for Pickup", "Dispatched", "Delivered"),
                        List.of(),
                        List.of("hero", "about", "services", "products", "contact"))),
                Map.entry("logistics", new VerticalConfig(
                        "logistics", "Logistics",
                        List.of("Booked", "Picked Up", "In Transit", "Out for Delivery", "Delivered"),
                        List.of(unit("weight", "Weight", "kg"), unit("length", "Length", "cm"),
                                unit("width", "Width", "cm"), unit("height", "Height", "cm")),
                        List.of("hero", "about", "services", "tracking", "contact"))),
                Map.entry("retail", new VerticalConfig(
                        "retail", "Retail / Shop",
                        List.of("Received", "Processing", "Packed", "Dispatched", "Delivered"),
                        List.of(),
                        List.of("hero", "about", "products", "gallery", "contact"))),
                Map.entry("professional-services", new VerticalConfig(
                        "professional-services", "Professional Services",
                        List.of("Requested", "Scheduled", "In Progress", "Completed", "Closed"),
                        List.of(),
                        List.of("hero", "about", "services", "team", "testimonials", "contact"))),
                Map.entry("food-and-beverage", new VerticalConfig(
                        "food-and-beverage", "Food & Beverage",
                        List.of("Received", "Preparing", "Ready", "Out for Delivery", "Delivered"),
                        List.of(),
                        List.of("hero", "about", "menu", "gallery", "contact"))),
                Map.entry("beauty-and-wellness", new VerticalConfig(
                        "beauty-and-wellness", "Beauty & Wellness",
                        List.of("Booked", "Confirmed", "In Service", "Completed"),
                        List.of(),
                        List.of("hero", "about", "services", "gallery", "testimonials", "contact"))),
                // Fallback vertical, addressable under both ids the rest of the stack uses.
                Map.entry("general", general),
                Map.entry("default", general));
    }

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
        return unit(key, label, "in");
    }

    private static MeasurementField unit(String key, String label, String unit) {
        return new MeasurementField(key, label, unit);
    }
}
