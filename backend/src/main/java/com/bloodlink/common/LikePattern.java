package com.bloodlink.common;

/**
 * Builds the pattern for a "contains" search — {@code column like :pattern escape '\'} —
 * from text a person typed, so that what they typed is matched literally.
 *
 * In a LIKE pattern {@code %} means "anything" and {@code _} means "any one
 * character". Left as they are, a search for "%" matches every row and a search for
 * "a_b" matches "axb", which is not what anyone typing them meant. Each is escaped
 * with a backslash here, and every query using this pattern names that backslash in
 * its {@code escape '\'} clause, so the database reads them as plain characters.
 * The backslash itself is escaped first, so a typed backslash is literal too.
 *
 * Shared by every search box that queries with LIKE (the blood bank's Donors roster
 * and the Admin Console's User Accounts), so the rule is written once.
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
