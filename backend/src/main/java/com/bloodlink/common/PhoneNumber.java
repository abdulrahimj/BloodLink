package com.bloodlink.common;

import java.util.regex.Pattern;

import com.bloodlink.web.error.InvalidPhoneNumberException;

/**
 * The one place a phone number is turned into the single format this system stores:
 * {@code +232} followed by exactly eight digits, no spaces and no leading zero —
 * for example {@code +23276900005}.
 *
 * <h2>Why this exists</h2>
 * Phone is the login credential (V2 made {@code users.phone} unique), and a
 * credential is compared by exact string equality. That made the stored format
 * load-bearing: a donor who registered as {@code 076900005} could not sign in as
 * {@code +232 76 900 005}, because those are three different strings for one
 * person's phone. Normalising both the value written at registration and the value
 * compared at login removes the mismatch at its source — the database ends up
 * holding one spelling per number, so there is only ever one thing to match.
 *
 * <h2>Why a single shared function, not a check in each place</h2>
 * Registration and login must agree exactly, always. Two copies of this rule would
 * be two copies free to drift, and the failure that drift produces is the worst
 * kind: an account that was created successfully and can never be signed into. So
 * every caller — {@code AuthService.register}, {@code AuthService.login}, and the
 * blood bank's donor lookup — goes through {@link #normalize(String)}, the same way
 * Business Rule 2's callers all go through {@code Donor.isEligibleToDonate()}.
 *
 * It sits in its own small package rather than inside {@code auth} or {@code donor}
 * because it belongs to neither and is used by both — and not in {@code domain},
 * whose classes are the persisted entities and deliberately depend on nothing
 * outside themselves.
 *
 * <h2>The rule</h2>
 * <ol>
 *   <li>All whitespace is removed, whichever format was typed.</li>
 *   <li>What remains must be digits, optionally preceded by a single {@code +}.
 *       Anything else — a letter, a hyphen, brackets, a second {@code +} — is
 *       refused, rather than stripped: a number containing a character that does
 *       not belong in one is more likely a typo than a formatting preference, and
 *       silently "fixing" it could store a different number than the one intended.</li>
 *   <li>A leading {@code 0} is the local format: the zero is dropped and
 *       {@code +232} put in its place.</li>
 *   <li>A leading {@code +232} is already the country code and is left alone.</li>
 *   <li>The result must be {@code +232} plus exactly eight digits. This is the check
 *       that catches a number of the wrong length whose characters were each
 *       individually fine ({@code 07690000}, {@code +2327690000512}), and also
 *       anything that reached this point without resolving to a Sierra Leone
 *       number at all ({@code 23276900005} with no {@code +}, {@code +447700900005}).</li>
 * </ol>
 *
 * Existing rows were converted to this format once, by {@code V11}, so accounts
 * created before this rule existed match it too.
 */
public final class PhoneNumber {

    /** Sierra Leone. The only country code this system stores. */
    public static final String COUNTRY_CODE = "+232";

    /** Digits after the country code: 8, e.g. {@code 76 900 005}. */
    private static final int NATIONAL_DIGITS = 8;

    /**
     * What a number may consist of once its spaces are gone: digits, with at most
     * one {@code +} and only at the very front. Deliberately not anchored on length
     * — length is the separate, final check, so a too-short number and a number
     * containing a letter are each rejected by the test that actually describes them.
     */
    private static final Pattern DIGITS_WITH_OPTIONAL_PLUS = Pattern.compile("\\+?[0-9]+");

    /** The finished format, which every stored and every compared value must match. */
    private static final Pattern NORMALIZED = Pattern.compile("\\+232[0-9]{" + NATIONAL_DIGITS + "}");

    /** Shown to the user on rejection, so the message names both accepted formats. */
    private static final String MESSAGE =
        "Enter a valid Sierra Leone phone number — 8 digits after the country code, "
            + "e.g. 076900005 or +232 76 900 005.";

    private PhoneNumber() {
        // Utility class: the rule has no state, and there is nothing to construct.
    }

    /**
     * Converts a typed phone number into the stored format, or refuses it.
     *
     * Accepts the local format ({@code 076900005}) and the international format
     * ({@code +23276900005}, {@code +232 76 900 005}) and returns the same string
     * for all of them, so a number typed one way at registration and the other way
     * at login compares equal.
     *
     * @param raw exactly what the user typed, spaces and all; may be null
     * @return the number as {@code +232} followed by eight digits
     * @throws InvalidPhoneNumberException if it is not a phone number this system
     *         can store — a 400 with the message above attached to the "phone"
     *         field, so the form can highlight the input that caused it
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new InvalidPhoneNumberException(MESSAGE);
        }

        // Strip every space (and any tab or newline pasted in with the number) —
        // "+232 76 900 005" and "+23276900005" are the same number typed twice.
        String compact = raw.replaceAll("\\s", "");

        // Refuse anything that is not digits with at most one leading "+". Done
        // before the conversion below so a value like "07690000a" is reported as
        // the malformed number it is, rather than becoming "+23276900 00a".
        if (!DIGITS_WITH_OPTIONAL_PLUS.matcher(compact).matches()) {
            throw new InvalidPhoneNumberException(MESSAGE);
        }

        // Local format: the leading 0 is the national trunk prefix, which the
        // country code replaces rather than joins. A value already starting "+"
        // cannot reach this branch, so there is no risk of mangling "+0...".
        String normalized = compact.startsWith("0")
            ? COUNTRY_CODE + compact.substring(1)
            : compact;

        // The length gate. Every character may have been valid and the number still
        // be one digit short, or carry a country code this system does not serve.
        if (!NORMALIZED.matcher(normalized).matches()) {
            throw new InvalidPhoneNumberException(MESSAGE);
        }

        return normalized;
    }
}
