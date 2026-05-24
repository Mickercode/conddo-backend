package io.conddo.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated slug must always satisfy the tenant slug rules (lowercase
 * alphanumerics + hyphens, 3–50 chars, starts/ends alphanumeric).
 */
class SlugsTest {

    private static final String SLUG_RULE = "^[a-z0-9](?:[a-z0-9-]{1,48}[a-z0-9])$";

    @Test
    void slugifiesTypicalBusinessNames() {
        assertEquals("amaka-styles", Slugs.from("Amaka Styles"));
        assertEquals("wellspring-pharmacy", Slugs.from("  Wellspring   Pharmacy  "));
        assertEquals("bright-co", Slugs.from("Bright & Co!!"));
    }

    @Test
    void padsShortOrEmptyNamesToAValidSlug() {
        assertEquals("biz-x", Slugs.from("X"));
        assertEquals("biz", Slugs.from("!!!"));
        assertEquals("biz", Slugs.from(null));
    }

    @Test
    void alwaysProducesAValidSlug() {
        for (String name : new String[]{"A", "Z!", "Café Déjà", "----", "My Business 123", "a".repeat(100)}) {
            String slug = Slugs.from(name);
            assertTrue(slug.matches(SLUG_RULE), "invalid slug '" + slug + "' for input '" + name + "'");
        }
    }
}
