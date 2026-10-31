package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.bloodlink.common.PasswordPolicy;

/**
 * The one-time staff password (Business Rule 14): two accounts never share one, every one meets the password rule,
 * and it can be read aloud without being misread.
 */
class TemporaryPasswordGeneratorTest {

    @Test
    void aPasswordIsLongEnoughToBeUnguessable() {
        // Twelve characters from a 66-character alphabet: far beyond what a login could be brute-forced through.
        assertThat(TemporaryPasswordGenerator.generate()).hasSize(12);
    }

    @Test
    void everyPasswordMeetsThePasswordRule() {
        // The account holder replaces it at first sign-in, but it is still a password the system set.
        IntStream.range(0, 2_000).forEach(i ->
            assertThat(PasswordPolicy.problemWith(TemporaryPasswordGenerator.generate())).isNull());
    }

    @Test
    void everyPasswordIsDifferent() {
        // A thousand draws with no repeat: a fixed or near-fixed generator could not pass.
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> seen.add(TemporaryPasswordGenerator.generate()));

        assertThat(seen).hasSize(1_000);
    }

    @Test
    void charactersThatAreMisreadWhenSpokenOrCopiedAreNeverUsed() {
        // It is read aloud or written down, so the look-alikes O, 0, I, l and 1 never appear.
        String everything = IntStream.range(0, 500)
            .mapToObj(i -> TemporaryPasswordGenerator.generate())
            .reduce("", String::concat);

        assertThat(everything).doesNotContain("O", "I", "l", "0", "1");
    }

    @Test
    void onlyLettersDigitsAndEasilyNamedSymbolsAreUsed() {
        // Symbols limited to ten with plain spoken names; no quotes, slashes, dots or spaces.
        String everything = IntStream.range(0, 200)
            .mapToObj(i -> TemporaryPasswordGenerator.generate())
            .reduce("", String::concat);

        assertThat(everything).matches("[A-Za-z0-9!@#$%&*+=?]+");
    }
}
