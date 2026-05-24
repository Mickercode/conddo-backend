package io.conddo.core.common;

/** Two-letter initials for avatars: "Amaka Styles" → "AS", "Amaka" → "AM". */
public final class Initials {

    private Initials() {
    }

    public static String of(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        String one = parts[0];
        return (one.length() >= 2 ? one.substring(0, 2) : one).toUpperCase();
    }
}
