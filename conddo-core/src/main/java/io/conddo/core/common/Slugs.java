package io.conddo.core.common;

import java.util.Locale;

/**
 * Derives a URL-safe tenant slug from a business name (e.g. "Amaka Styles" →
 * "amaka-styles"). Produces a value that satisfies the tenant slug rules
 * (lowercase alphanumerics + hyphens, 3–50 chars, starts/ends alphanumeric).
 * Uniqueness is handled separately by the caller.
 */
public final class Slugs {

    private static final int MAX_LENGTH = 40; // leaves room for a "-N" uniqueness suffix

    private Slugs() {
    }

    public static String from(String input) {
        if (input == null) {
            return "biz";
        }
        String slug = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")   // runs of non-alphanumerics → single hyphen
                .replaceAll("(^-+)|(-+$)", "");   // trim leading/trailing hyphens
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        if (slug.length() < 3) {
            slug = slug.isEmpty() ? "biz" : "biz-" + slug;
        }
        return slug;
    }
}
