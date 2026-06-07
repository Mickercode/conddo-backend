package io.conddo.core.registry;

import io.conddo.core.registry.UiManifest.NavItem;
import io.conddo.core.registry.UiManifest.Route;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps active capability-tool ids to their {@link UiManifest} (Architecture §16),
 * so the frontend can build its sidebar/routes from the JWT's {@code activeModules}
 * with zero hardcoding. Several tool ids map to the same nav section
 * (e.g. {@code crm} + {@code crm.pharmacy} → Customers; all {@code marketing.*} →
 * Marketing), so the result is de-duplicated to one manifest per section.
 *
 * <p>Interim Java home; the v1.0 target derives this from each tool's
 * {@code CapabilityTool.getUIManifest()}. Same wire contract either way.
 */
@Component
public class ManifestCatalogue {

    /** Distinct nav sections, keyed by a section id, in default sidebar order. */
    private final Map<String, NavItem> sections = new LinkedHashMap<>();
    /** tool id → section id. */
    private final Map<String, String> toolToSection = new LinkedHashMap<>();

    public ManifestCatalogue() {
        section("website", "Website", "globe", "/website", 20, "website");
        section("customers", "Customers", "users", "/customers", 30, "crm", "crm.pharmacy");
        section("tracking", "Tracking", "truck", "/tracking", 42, "tracking.advanced");
        section("prescriptions", "Prescriptions", "pill", "/prescriptions", 45, "prescriptions");
        section("consultations", "Consultations", "message-circle", "/consultations", 46, "consultations");
        section("orders", "Orders", "clipboard-list", "/orders", 40,
                "orders", "orders.fashion", "orders.logistics");
        // Fashion deep-dive: Fabric is the specialized inventory view (rolls of
        // cloth with colour + supplier + yards remaining), Fittings is the
        // specialized booking view (fitting appointments tied to a garment job).
        section("fabric", "Fabric", "layers", "/fabric", 46, "fabric.fashion");
        section("tables", "Tables", "utensils", "/tables", 48, "table-mgmt");
        section("inventory", "Inventory", "package", "/inventory", 50,
                "inventory", "inventory.pharmacy", "inventory.retail");
        section("fittings", "Fittings", "scissors", "/fittings", 58, "fittings.fashion");
        section("store", "Store", "shopping-cart", "/store", 52, "ecommerce");
        section("pos", "POS", "scan-line", "/pos", 55, "pos", "pos.pharmacy");
        section("bookings", "Bookings", "calendar", "/bookings", 60, "bookings");
        section("documents", "Documents", "folder", "/documents", 65, "document-vault");
        section("payments", "Payments", "wallet", "/payments", 70, "payments");
        section("marketing", "Marketing", "megaphone", "/marketing", 80,
                "marketing.social", "marketing.email", "marketing.sms", "marketing.ads", "marketing.leads");
        section("loyalty", "Loyalty", "gift", "/loyalty", 85, "loyalty");
        section("analytics", "Analytics", "bar-chart-3", "/analytics", 90, "analytics", "analytics.pharmacy");
        section("staff", "Staff", "user-cog", "/staff", 100, "staff");
    }

    /** Manifests for the given active tool ids — one per distinct nav section, in sidebar order. */
    public List<UiManifest> forModules(Collection<String> toolIds) {
        Set<String> sectionIds = new LinkedHashSet<>();
        for (String toolId : toolIds) {
            String section = toolToSection.get(toolId);
            if (section != null) {
                sectionIds.add(section);
            }
        }
        return sectionIds.stream()
                .map(this::manifestFor)
                .sorted((a, b) -> Integer.compare(a.navItem().order(), b.navItem().order()))
                .toList();
    }

    private UiManifest manifestFor(String sectionId) {
        NavItem nav = sections.get(sectionId);
        String component = Character.toUpperCase(sectionId.charAt(0)) + sectionId.substring(1) + "Page";
        return new UiManifest(sectionId, nav, List.of(new Route(nav.path(), component)), List.of());
    }

    private void section(String id, String label, String icon, String path, int order, String... toolIds) {
        sections.put(id, new NavItem(label, icon, path, order));
        for (String toolId : toolIds) {
            toolToSection.put(toolId, id);
        }
    }

    /** Exposed for tests/inspection: the tool ids this catalogue knows how to render. */
    public List<String> knownToolIds() {
        return new ArrayList<>(toolToSection.keySet());
    }
}
