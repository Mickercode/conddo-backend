package io.conddo.core.signup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises every branch of the {@link WebsiteTypeResolver} decision tree, plus
 * the case-insensitive / blank-input handling.
 */
class WebsiteTypeResolverTest {

    private final WebsiteTypeResolver resolver = new WebsiteTypeResolver();

    @Test
    void proRetailGetsEcommerce() {
        WebsiteTypeRecommendation rec = resolver.resolve("retail", "pro");
        assertEquals(WebsiteType.ECOMMERCE, rec.type());
        assertTrue(rec.sections().contains("products"));
        assertNotNull(rec.reasoning());
    }

    @Test
    void beautyVerticalGetsBookingFocused() {
        WebsiteTypeRecommendation rec = resolver.resolve("beauty-and-wellness", "starter");
        assertEquals(WebsiteType.BOOKING_FOCUSED, rec.type());
        assertTrue(rec.sections().contains("book"));
    }

    @Test
    void professionalServicesGetsBookingEvenOnPro() {
        WebsiteTypeRecommendation rec = resolver.resolve("professional-services", "pro");
        assertEquals(WebsiteType.BOOKING_FOCUSED, rec.type());
    }

    @Test
    void starterFashionGetsLandingPage() {
        WebsiteTypeRecommendation rec = resolver.resolve("fashion", "starter");
        assertEquals(WebsiteType.LANDING_PAGE, rec.type());
        assertEquals(4, rec.sections().size());
    }

    @Test
    void starterFoodAndBeverageGetsLandingPage() {
        WebsiteTypeRecommendation rec = resolver.resolve("food-and-beverage", "starter");
        assertEquals(WebsiteType.LANDING_PAGE, rec.type());
    }

    @Test
    void businessFashionFallsThroughToMultiPage() {
        WebsiteTypeRecommendation rec = resolver.resolve("fashion", "business");
        assertEquals(WebsiteType.MULTI_PAGE, rec.type());
    }

    @Test
    void pharmacyAlwaysMultiPage() {
        assertEquals(WebsiteType.MULTI_PAGE, resolver.resolve("pharmacy", "starter").type());
        assertEquals(WebsiteType.MULTI_PAGE, resolver.resolve("pharmacy", "business").type());
        assertEquals(WebsiteType.MULTI_PAGE, resolver.resolve("pharmacy", "pro").type());
    }

    @Test
    void retailOnStarterFallsThroughToMultiPage() {
        // pro+retail is ecommerce; lower tiers shouldn't promise a storefront.
        assertEquals(WebsiteType.MULTI_PAGE, resolver.resolve("retail", "starter").type());
        assertEquals(WebsiteType.MULTI_PAGE, resolver.resolve("retail", "business").type());
    }

    @Test
    void inputsAreCaseAndWhitespaceInsensitive() {
        assertEquals(WebsiteType.ECOMMERCE,
                resolver.resolve("  RETAIL ", " Pro ").type());
        assertEquals(WebsiteType.BOOKING_FOCUSED,
                resolver.resolve("BEAUTY-AND-WELLNESS", "STARTER").type());
    }

    @Test
    void blankInputsGetMultiPage() {
        assertEquals(WebsiteType.MULTI_PAGE, resolver.resolve(null, null).type());
        assertEquals(WebsiteType.MULTI_PAGE, resolver.resolve("", "").type());
    }
}
