package com.bloodlink.common;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one rule for every password set or changed (8–72 characters, a capital, a small letter, a number, a special
 * character); stored passwords are never re-judged. frontend/src/lib/passwordRules.js is its twin, word for word.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    /** The five parts of the rule, in the order the checklist and the messages name them. */
    public enum Requirement {
        LENGTH("at least 8 characters"),
        UPPERCASE("a capital letter"),
        LOWERCASE("a small letter"),
        DIGIT("a number"),
        SPECIAL("a special character");

        private final String description;

        Requirement(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    /** Anything but a letter, a number or whitespace, in any script; spelled out so JavaScript's twin matches it exactly. */
    private static final Pattern SPECIAL = Pattern.compile("[^\\p{L}\\p{N}\\p{Z}\\t\\n\\x0B\\f\\r]");

    private PasswordPolicy() {
    }

    /** The parts the password does not meet yet (empty when it meets them all); the 72 maximum is separate. */
    public static Set<Requirement> missing(String password) {
        String value = password == null ? "" : password;
        Set<Requirement> missing = EnumSet.noneOf(Requirement.class);
        if (value.length() < MIN_LENGTH) missing.add(Requirement.LENGTH);
        if (!UPPERCASE.matcher(value).find()) missing.add(Requirement.UPPERCASE);
        if (!LOWERCASE.matcher(value).find()) missing.add(Requirement.LOWERCASE);
        if (!DIGIT.matcher(value).find()) missing.add(Requirement.DIGIT);
        if (!SPECIAL.matcher(value).find()) missing.add(Requirement.SPECIAL);
        return missing;
    }

    /**
     * Why the password is refused, as one sentence naming every missing part ("Add a capital letter and a special
     * character."), or null when it is acceptable. Never repeats the password itself.
     */
    public static String problemWith(String password) {
        Set<Requirement> missing = missing(password);
        List<String> parts = new ArrayList<>();
        if (missing.contains(Requirement.LENGTH)) {
            parts.add("use at least " + MIN_LENGTH + " characters");
        } else if (password.length() > MAX_LENGTH) {
            parts.add("use no more than " + MAX_LENGTH + " characters");
        }
        List<String> toAdd = missing.stream().filter(r -> r != Requirement.LENGTH).map(Requirement::description).toList();
        if (!toAdd.isEmpty()) {
            parts.add("add " + joinWithAnd(toAdd));
        }
        if (parts.isEmpty()) {
            return null;
        }
        String sentence = String.join(", and ", parts) + ".";
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    /** "a", "a and b", "a, b and c". */
    private static String joinWithAnd(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
