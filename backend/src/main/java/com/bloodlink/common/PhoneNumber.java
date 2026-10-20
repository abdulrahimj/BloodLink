package com.bloodlink.common;

import java.util.regex.Pattern;

import com.bloodlink.web.error.InvalidPhoneNumberException;

/**
 * The single phone format this system stores, +232 plus eight digits (Business Rule 13): phone is the login,
 * so register, login and donor lookup all call normalize; V11 converted existing rows.
 */
public final class PhoneNumber {

    /** Sierra Leone. The only country code this system stores. */
    public static final String COUNTRY_CODE = "+232";

    /** Digits after the country code: 8, e.g. {@code 76 900 005}. */
    private static final int NATIONAL_DIGITS = 8;

    /**
     * Digits with at most one leading +; length is checked separately so each mistake gets the test describing it.
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
     * Converts a typed number, local (076900005) or international (+232 76 900 005), to +232 plus eight digits.
     * Throws InvalidPhoneNumberException (a 400 on "phone") if it cannot be stored.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new InvalidPhoneNumberException(MESSAGE);
        }

        // Strip every space (and any tab or newline pasted in with the number) —
        // "+232 76 900 005" and "+23276900005" are the same number typed twice.
        String compact = raw.replaceAll("\\s", "");

        // Refuse anything but digits with one leading "+" before converting,
        // so "07690000a" is reported as malformed rather than mangled.
        if (!DIGITS_WITH_OPTIONAL_PLUS.matcher(compact).matches()) {
            throw new InvalidPhoneNumberException(MESSAGE);
        }

        // Local format: the trunk prefix 0 is replaced by the country code; a value starting "+" never reaches here.
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

    /**
     * Turns search text into a fragment of a stored phone: spaces and hyphens removed, a leading 0 becomes +232.
     * Not normalize, which would refuse partial searches; never throws. Used by the Donors and User Accounts searches.
     */
    public static String searchFragment(String typed) {
        String compact = typed.replaceAll("[\\s-]", "");
        return compact.startsWith("0") ? COUNTRY_CODE + compact.substring(1) : compact;
    }
}
