package com.bloodlink.common;

import java.util.regex.Pattern;

import com.bloodlink.web.error.InvalidFieldException;

/**
 * The shape an email address must have to be stored for a donor — Business Rule 18.
 *
 * <h2>Format only, on purpose</h2>
 * This checks that an address looks like a real one — {@code name@domain.tld} — and
 * nothing more. It does not look the domain up or try to deliver anything. That
 * would mean a network call in the middle of registration, and BloodLink has to keep
 * working on the intermittent connections common in Makeni: a donor signing up at a
 * blood drive must not be turned away because a DNS server did not answer. What this
 * catches is the thing that actually goes wrong at a registration desk — typos.
 *
 * <h2>Why not the built-in {@code @Email}</h2>
 * Bean Validation's {@code @Email} follows the email standard's full grammar, which
 * is far looser than real-world addresses: it accepts {@code name@gmail} with no
 * top-level domain at all, which is exactly the typo this exists to catch. The
 * pattern here requires what every address a donor actually has will have: a local
 * part, one {@code @}, at least one dot in the domain, and a letters-only ending of
 * two or more characters.
 *
 * <h2>What is refused</h2>
 * A missing or doubled {@code @}; no dot after the {@code @} ({@code name@gmail});
 * a one-letter ending ({@code name@gmail.c}); a dot at the start or end of the name,
 * or two dots in a row anywhere ({@code name@gmail..com}); spaces or other characters
 * that do not belong in an address; and anything longer than the standard's limits
 * (64 before the {@code @}, 254 overall).
 *
 * <h2>What is deliberately not supported</h2>
 * Addresses written in non-Latin scripts (internationalised addresses). They are
 * valid, but rare enough here that refusing them costs less than the typos a looser
 * pattern would let through — and since email is optional for donors, someone with
 * such an address can still register, just without it.
 */
public final class EmailAddress {

    /**
     * Applied after trimming and lower-casing, so it only needs lower-case letters.
     *   - local part: letters, digits and . _ % + - ; not starting or ending with a
     *     dot (the look-ahead and look-behind), no two dots in a row anywhere (the
     *     second look-ahead)
     *   - domain: one or more labels, each letters/digits/hyphens, not starting or
     *     ending with a hyphen, each followed by a dot
     *   - top-level domain: 2 to 24 letters
     */
    private static final Pattern FORMAT = Pattern.compile(
        "^(?!\\.)(?!.*\\.\\.)[a-z0-9._%+-]+(?<!\\.)"
            + "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
            + "[a-z]{2,24}$"
    );

    private static final int MAX_LOCAL_PART = 64;
    private static final int MAX_LENGTH = 254;

    private static final String MESSAGE =
        "Enter a valid email address, like name@example.com — or leave it blank.";

    private EmailAddress() {
        // Utility class.
    }

    /**
     * Normalises an optional email address, or refuses it.
     *
     * Blank (null, empty or only spaces) means "no email" and becomes null — never
     * an empty string, which V13's constraint forbids storing, because two empty
     * strings would collide on the unique index where two NULLs do not. Anything
     * else is trimmed and lower-cased (so {@code Name@Example.com} and
     * {@code name@example.com} are one address), then checked against the format.
     *
     * @return the address as it should be stored, or null for none
     * @throws InvalidFieldException on the "email" field if the format is wrong —
     *         a 400 the registration form shows under the email box
     */
    public static String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String email = raw.trim().toLowerCase();
        int at = email.indexOf('@');
        boolean withinLimits = email.length() <= MAX_LENGTH && at > 0 && at <= MAX_LOCAL_PART;

        if (!withinLimits || !FORMAT.matcher(email).matches()) {
            throw new InvalidFieldException("email", MESSAGE);
        }
        return email;
    }
}
