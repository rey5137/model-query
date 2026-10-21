package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * How a {@link Filters#like} value, and a catalogue operator's such as {@code ilike}, becomes a {@code LIKE} pattern.
 *
 * @implSpec R-FLT-06
 */
@Incubating
public enum LikeMode {
    /** The value is the pattern, passed through as given: its {@code %} and {@code _} are wildcards. */
    EXACT,
    /** Matches the value anywhere; {@code %}, {@code _} and {@code \} in it match literally. */
    CONTAINS,
    /** Matches values starting with the value; {@code %}, {@code _} and {@code \} in it match literally. */
    STARTS_WITH,
    /** Matches values ending with the value; {@code %}, {@code _} and {@code \} in it match literally. */
    ENDS_WITH;

    private static final char ESCAPE = '\\';

    /**
     * The pattern for {@code value}: as given for {@code EXACT}, otherwise with {@code %}, {@code _} and the
     * {@linkplain #escapeCharacter() escape character} escaped and wrapped in {@code %} as the mode says.
     */
    @EngineFacing
    public String pattern(String value) {
        return switch (this) {
            case EXACT -> value;
            case CONTAINS -> "%" + escape(value) + "%";
            case STARTS_WITH -> escape(value) + "%";
            case ENDS_WITH -> "%" + escape(value);
        };
    }

    /** The escape character a {@link #pattern} of every mode but {@code EXACT} is rendered with: {@code \}. */
    @EngineFacing
    public static char escapeCharacter() {
        return ESCAPE;
    }

    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
