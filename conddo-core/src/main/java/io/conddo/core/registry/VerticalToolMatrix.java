package io.conddo.core.registry;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the capability-tool set a tenant gets for its {@code vertical × plan}
 * (Architecture v1.0 §5, the matrix in {@code VERTICALS.md}). Plan tiers are
 * cumulative: business includes all of starter, pro all of business. This backs
 * the JWT {@code activeModules} claim (§4.4) and the manifest endpoint (§16).
 *
 * <p>Interim home: the matrix is encoded here in Java. The v1.0 target is one
 * YAML file per vertical loaded by the Module Registry; moving it there is a
 * clean follow-up that won't change this contract.
 */
@Component
public class VerticalToolMatrix {

    /** Used when a tenant's vertical isn't one of the seven canonical ones (e.g. "general"). */
    public static final String DEFAULT_VERTICAL = "default";

    private static final List<String> PLAN_ORDER = List.of("starter", "business", "pro");

    /** vertical → (tier → cumulative tool list). */
    private final Map<String, Map<String, List<String>>> matrix;

    public VerticalToolMatrix() {
        this.matrix = Map.ofEntries(
                Map.entry("pharmacy", tiers(
                        List.of("website", "crm.pharmacy", "inventory.pharmacy", "pos.pharmacy",
                                "prescriptions", "payments", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms"),
                        List.of("marketing.ads", "analytics.pharmacy"))),
                // Fashion — orders is the headline (made-to-measure jobs), but the
                // vertical-specific deep-dive screens are Fittings (specialized
                // bookings) and Fabric (specialized inventory). Both ship in the
                // starter tier so every fashion tenant sees them on day one.
                Map.entry("fashion", tiers(
                        List.of("website", "crm", "orders.fashion", "fittings.fashion",
                                "fabric.fashion", "payments", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms", "marketing.leads"),
                        List.of("marketing.ads"))),
                Map.entry("logistics", tiers(
                        List.of("website", "crm", "orders.logistics", "payments", "analytics"),
                        List.of("staff", "marketing.social", "marketing.sms"),
                        List.of("marketing.ads", "tracking.advanced"))),
                Map.entry("retail", tiers(
                        List.of("website", "crm", "inventory.retail", "pos", "payments", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms"),
                        List.of("marketing.ads", "ecommerce"))),
                Map.entry("professional-services", tiers(
                        List.of("website", "crm", "bookings", "payments", "document-vault", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms", "marketing.leads"),
                        List.of("marketing.ads"))),
                Map.entry("food-and-beverage", tiers(
                        List.of("website", "crm", "orders", "payments", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms", "table-mgmt"),
                        List.of("marketing.ads"))),
                Map.entry("beauty-and-wellness", tiers(
                        List.of("website", "crm", "bookings", "payments", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms", "loyalty"),
                        List.of("marketing.ads"))),
                // Music studio — covers recording studios (Type A) and music schools (Type B).
                // Bookings is the headline module (rooms/booths/lesson slots), payments
                // handles deposit-at-booking — the killer feature for stopping ghost bookings.
                Map.entry("music-studio", tiers(
                        List.of("website", "crm", "bookings", "payments", "inventory", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms", "projects"),
                        List.of("marketing.ads", "music-school"))),
                // Fallback for unknown/"general" verticals: a generic orders-centric business.
                Map.entry(DEFAULT_VERTICAL, tiers(
                        List.of("website", "crm", "orders", "bookings", "inventory", "payments", "analytics"),
                        List.of("staff", "marketing.social", "marketing.email", "marketing.sms", "marketing.leads"),
                        List.of("marketing.ads"))));
    }

    /** The active tool ids for a tenant's vertical + plan (never null). */
    public List<String> resolve(String vertical, String plan) {
        Map<String, List<String>> byTier = matrix.getOrDefault(
                normalizeVertical(vertical), matrix.get(DEFAULT_VERTICAL));
        return byTier.getOrDefault(normalizePlan(plan), byTier.get("starter"));
    }

    /**
     * Normalise a stored plan to a known tier; unknown/blank/"free" → starter.
     * Handles both legacy tier names ({@code starter/business/pro}) and the new
     * product names introduced by BILLING_TIERS_SPEC ({@code launcher/growth/scaler}).
     * The matrix keys stay on the tier axis — translating here keeps the seven
     * vertical entries above from needing per-rebrand edits.
     */
    public String normalizePlan(String plan) {
        String p = plan == null ? "" : plan.trim().toLowerCase();
        // New product names → matrix tier names. Tier names pass through.
        String mapped = switch (p) {
            case "launcher" -> "starter";
            case "growth"   -> "business";
            case "scaler"   -> "pro";
            default         -> p;
        };
        return PLAN_ORDER.contains(mapped) ? mapped : "starter";
    }

    private String normalizeVertical(String vertical) {
        String v = vertical == null ? "" : vertical.trim().toLowerCase();
        return matrix.containsKey(v) ? v : DEFAULT_VERTICAL;
    }

    /** Builds the cumulative starter/business/pro lists from each tier's additions. */
    private static Map<String, List<String>> tiers(List<String> starter, List<String> businessAdds,
                                                    List<String> proAdds) {
        List<String> business = cumulative(starter, businessAdds);
        List<String> pro = cumulative(business, proAdds);
        return Map.of("starter", List.copyOf(starter), "business", business, "pro", pro);
    }

    private static List<String> cumulative(List<String> base, List<String> adds) {
        Set<String> all = new LinkedHashSet<>(base);
        all.addAll(adds);
        return List.copyOf(all);
    }
}
