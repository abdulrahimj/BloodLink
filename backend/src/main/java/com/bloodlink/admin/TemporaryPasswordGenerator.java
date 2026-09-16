package com.bloodlink.admin;

import java.security.SecureRandom;

/**
 * Makes the one-time password a new staff account is created with.
 *
 * <h2>Why the system generates it rather than the administrator</h2>
 * An administrator typing a password for someone else picks a memorable one, reuses
 * it across the accounts they create, and knows it afterwards. A generated one is
 * none of those things: it is unguessable, different every time, and the plaintext
 * exists only in the single response that returns it (Business Rule 14). The new
 * staff member should change it after signing in — but even if they never do, no
 * two accounts share a password and nothing weak was chosen out of convenience.
 *
 * <h2>The alphabet</h2>
 * Letters and digits only, with the characters people confuse when reading a
 * password aloud or copying it off a screen removed: capital O and digit 0, capital
 * I, lowercase l and digit 1. This password is relayed by a person to another person
 * — read over a phone, written on a slip — so a character that invites a typo costs
 * a failed sign-in and a call back to the administrator. Punctuation is left out for
 * the same reason, not for any security one: what is lost in alphabet size is bought
 * back with length.
 *
 * Twelve characters from a 54-character alphabet is about 69 bits of entropy, which
 * is far beyond what a login endpoint could ever be brute-forced through.
 */
final class TemporaryPasswordGenerator {

    /** Deliberately missing: O, I, l, 0, 1 — see the class comment. */
    private static final String ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnpqrstuvwxyz" + "23456789";

    private static final int LENGTH = 12;

    /**
     * SecureRandom, not Random: this value is a credential. Random is seeded from
     * the clock and its output is predictable to anyone who can guess when the
     * account was created, which — for an account list that shows a "Joined"
     * timestamp — is everyone.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {
        // Utility class.
    }

    /**
     * A fresh 12-character password.
     *
     * Every character is drawn independently and uniformly via nextInt(bound), which
     * rejects the biased tail internally rather than taking a remainder — so no
     * character is more likely to appear than any other.
     */
    static String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
