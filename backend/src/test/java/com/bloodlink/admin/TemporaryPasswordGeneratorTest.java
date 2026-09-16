package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * The one-time password a new staff account is created with (Business Rule 14).
 *
 * Two things are worth testing about a generated credential, and neither is its
 * exact value: that two accounts never share one, and that it can actually be
 * relayed by a person to another person without being misread — which is the whole
 * reason its alphabet is smaller than it could be.
 */
class TemporaryPasswordGeneratorTest {

    @Test
    void aPasswordIsLongEnoughToBeUnguessable() {
        // Twelve characters from a 54-character alphabet — far beyond what a login
        // endpoint could be brute-forced through, and still short enough to read out.
        assertThat(TemporaryPasswordGenerator.generate()).hasSize(12);
    }

    @Test
    void everyPasswordIsDifferent() {
        // The property that matters most: an administrator creating five accounts in
        // a row must not hand out one password five times. A thousand draws with no
        // repeat is not proof, but a generator with a fixed or near-fixed value
        // cannot survive it.
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> seen.add(TemporaryPasswordGenerator.generate()));

        assertThat(seen).hasSize(1_000);
    }

    @Test
    void charactersThatAreMisreadWhenSpokenOrCopiedAreNeverUsed() {
        // This password gets read over a phone or written on a slip of paper. A
        // capital O next to a zero costs a failed sign-in and a call back to the
        // administrator, so neither appears — nor I, l or 1.
        String everything = IntStream.range(0, 500)
            .mapToObj(i -> TemporaryPasswordGenerator.generate())
            .reduce("", String::concat);

        assertThat(everything).doesNotContain("O", "I", "l", "0", "1");
    }

    @Test
    void onlyLettersAndDigitsAreUsed() {
        // No punctuation: what is lost in alphabet size is bought back with length,
        // and a password containing a character someone has to describe out loud
        // ("underscore, then...") is a password that arrives wrong.
        String everything = IntStream.range(0, 200)
            .mapToObj(i -> TemporaryPasswordGenerator.generate())
            .reduce("", String::concat);

        assertThat(everything).matches("[A-Za-z0-9]+");
    }
}
