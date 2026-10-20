package com.bloodlink.common;

/**
 * Builds a LIKE "contains" pattern from typed text with \, % and _ escaped, so the text matches literally
 * (queries use escape '\'). Shared by the Donors roster and User Accounts searches.
 */
public final class LikePattern {

    private LikePattern() {
        // Utility class: no state.
    }

    /** {@code 50%_off} becomes {@code 50\%\_off}. */
    public static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The escaped text wrapped in {@code %…%}: "matches anywhere in the value". */
    public static String contains(String text) {
        return "%" + escape(text) + "%";
    }
}
