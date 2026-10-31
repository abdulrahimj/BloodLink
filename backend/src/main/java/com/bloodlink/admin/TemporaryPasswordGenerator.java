package com.bloodlink.admin;

import java.security.SecureRandom;

import com.bloodlink.common.PasswordPolicy;

/**
 * Generates the one-time password for a new staff account (Business Rule 14): 12 characters meeting PasswordPolicy.
 * No look-alikes (O, 0, I, l, 1) and only easily named symbols, since it is read aloud to another person.
 */
final class TemporaryPasswordGenerator {

    /** Deliberately missing: O, I, l, 0, 1 and hard-to-say punctuation — see the class comment. */
    private static final String ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnpqrstuvwxyz" + "23456789" + "!@#$%&*+=?";

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
     * A fresh 12-character password, each character drawn uniformly; a draw missing any part of the rule is thrown
     * away and redrawn (about one in three), so every password meets it and all valid ones stay equally likely.
     */
    static String generate() {
        while (true) {
            StringBuilder password = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            if (PasswordPolicy.problemWith(password.toString()) == null) {
                return password.toString();
            }
        }
    }
}
