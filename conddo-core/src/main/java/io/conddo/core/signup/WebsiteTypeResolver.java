package io.conddo.core.signup;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Picks the right {@link WebsiteType} for a newly activated tenant from its
 * vertical and plan. Some businesses only need a single landing page; others
 * are booking-centric or need an actual storefront — auto-creating the same
 * generic multi-page job for everyone wastes Studio time. The verdict is
 * embedded in the Studio job brief at intake.
 *
 * <p>Rules (first match wins):
 * <ol>
 *   <li>{@code pro} plan + retail → {@link WebsiteType#ECOMMERCE}.</li>
 *   <li>Service-led verticals (beauty-and-wellness, professional-services) →
 *       {@link WebsiteType#BOOKING_FOCUSED}.</li>
 *   <li>{@code starter} plan + a "simple" vertical (general, food-and-beverage,
 *       fashion) → {@link WebsiteType#LANDING_PAGE}.</li>
 *   <li>Otherwise → {@link WebsiteType#MULTI_PAGE} (the standard small-business site).</li>
 * </ol>
 *
 * <p>Deterministic and pure — no I/O, no model calls. AI-powered refinement is
 * a clean follow-up (the {@link io.conddo.core.signup} layer is the seam).
 */
@Component
public class WebsiteTypeResolver {

    private static final Set<String> BOOKING_VERTICALS = Set.of(
            "beauty-and-wellness", "professional-services");

    private static final Set<String> LANDING_PAGE_STARTER_VERTICALS = Set.of(
            "general", "food-and-beverage", "fashion");

    private static final List<String> LANDING_SECTIONS =
            List.of("hero", "about", "services", "contact");

    private static final List<String> BOOKING_SECTIONS =
            List.of("hero", "services", "book", "about", "contact");

    private static final List<String> ECOMMERCE_SECTIONS =
            List.of("hero", "featured", "products", "about", "contact");

    private static final List<String> MULTI_PAGE_SECTIONS =
            List.of("hero", "about", "services", "gallery", "testimonials", "contact");

    public WebsiteTypeRecommendation resolve(String verticalId, String planId) {
        String vertical = verticalId == null ? "" : verticalId.trim().toLowerCase();
        String plan = planId == null ? "" : planId.trim().toLowerCase();

        if ("pro".equals(plan) && "retail".equals(vertical)) {
            return new WebsiteTypeRecommendation(WebsiteType.ECOMMERCE, ECOMMERCE_SECTIONS,
                    "Pro plan + retail vertical — full storefront with products.");
        }
        if (BOOKING_VERTICALS.contains(vertical)) {
            return new WebsiteTypeRecommendation(WebsiteType.BOOKING_FOCUSED, BOOKING_SECTIONS,
                    "Service-led vertical — booking is the centrepiece.");
        }
        if ("starter".equals(plan) && LANDING_PAGE_STARTER_VERTICALS.contains(vertical)) {
            return new WebsiteTypeRecommendation(WebsiteType.LANDING_PAGE, LANDING_SECTIONS,
                    "Starter plan + simple vertical — single landing page focused on lead capture.");
        }
        return new WebsiteTypeRecommendation(WebsiteType.MULTI_PAGE, MULTI_PAGE_SECTIONS,
                "Standard multi-section small-business website.");
    }
}
