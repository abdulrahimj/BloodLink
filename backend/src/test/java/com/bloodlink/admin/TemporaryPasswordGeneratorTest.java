package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * The one-time staff password (Business Rule 14): two accounts never share one,
 * and it can be read aloud without being misread.
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
    void onlyLettersAndDigitsAreUsed() {
        // No punctuation, since a character that has to be described out loud arrives wrong.
        String everything = IntStream.range(0, 200)
            .mapToObj(i -> TemporaryPasswordGenerator.generate())
            .reduce("", String::concat);

        assertThat(everything).matches("[A-Za-z0-9]+");
    }
}
