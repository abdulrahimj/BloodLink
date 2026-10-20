package com.bloodlink.admin;

import java.security.SecureRandom;

/**
 * Generates the one-time password for a new staff account (Business Rule 14): 12 characters, about 69 bits of entropy.
 * Letters and digits only, without the look-alikes O, 0, I, l and 1, since it is read aloud to another person.
 */
final class TemporaryPasswordGenerator {

    /** Deliberately missing: O, I, l, 0, 1 — see the class comment. */
    private static final String ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnpqrstuvwxyz" + "23456789";

    private static final int LENGTH = 12;

    /**
     * SecureRandom, not Random: this value is a credential,
     * and Random's clock-based seed would be guessable from the account's creation time.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {
        // Utility class.
    }

    /**
     * A fresh 12-character password, each character drawn independently and uniformly.
     */
    static String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
