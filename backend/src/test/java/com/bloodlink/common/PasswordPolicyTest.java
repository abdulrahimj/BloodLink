package com.bloodlink.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.bloodlink.common.PasswordPolicy.Requirement;

/** The shared password rule: each missing part named, the 8 and 72 limits, and what counts as special. */
class PasswordPolicyTest {

    @Test
    void aPasswordMeetingEveryPartIsAccepted() {
        assertThat(PasswordPolicy.problemWith("Password123!")).isNull();
        assertThat(PasswordPolicy.missing("Password123!")).isEmpty();
    }

    @Test
    void eachMissingPartIsNamedOnItsOwn() {
        assertThat(PasswordPolicy.problemWith("Pass123!")).isNull();
        assertThat(PasswordPolicy.problemWith("Pa12!")).isEqualTo("Use at least 8 characters.");
        assertThat(PasswordPolicy.problemWith("password123!")).isEqualTo("Add a capital letter.");
        assertThat(PasswordPolicy.problemWith("PASSWORD123!")).isEqualTo("Add a small letter.");
        assertThat(PasswordPolicy.problemWith("Password!!!!")).isEqualTo("Add a number.");
        assertThat(PasswordPolicy.problemWith("Password1234")).isEqualTo("Add a special character.");
    }

    @Test
    void severalMissingPartsAreNamedTogether() {
        assertThat(PasswordPolicy.problemWith("password123")).isEqualTo("Add a capital letter and a special character.");
        assertThat(PasswordPolicy.problemWith("password")).isEqualTo("Add a capital letter, a number and a special character.");
        assertThat(PasswordPolicy.problemWith("abc"))
            .isEqualTo("Use at least 8 characters, and add a capital letter, a number and a special character.");
        assertThat(PasswordPolicy.problemWith(""))
            .isEqualTo("Use at least 8 characters, and add a capital letter, a small letter, a number and a special character.");
        assertThat(PasswordPolicy.missing(null)).containsExactly(Requirement.values());
    }

    @Test
    void eightAndSeventyTwoCharactersAreAcceptedSevenAndSeventyThreeRefused() {
        assertThat(PasswordPolicy.problemWith("Abcde1!x")).isNull();
        assertThat(PasswordPolicy.problemWith("Abcd1!x")).isEqualTo("Use at least 8 characters.");
        String longest = "Aa1!" + "x".repeat(68);
        assertThat(longest).hasSize(72);
        assertThat(PasswordPolicy.problemWith(longest)).isNull();
        assertThat(PasswordPolicy.problemWith(longest + "x")).isEqualTo("Use no more than 72 characters.");
        assertThat(PasswordPolicy.problemWith("aa1!" + "x".repeat(69)))
            .isEqualTo("Use no more than 72 characters, and add a capital letter.");
    }

    @Test
    void whitespaceIsNotASpecialCharacter() {
        assertThat(PasswordPolicy.problemWith("Password 123")).isEqualTo("Add a special character.");
        assertThat(PasswordPolicy.problemWith("Password\t123")).isEqualTo("Add a special character.");
        assertThat(PasswordPolicy.problemWith("Password 123")).isEqualTo("Add a special character.");
        assertThat(PasswordPolicy.problemWith("Pass word1!")).isNull();
    }

    @Test
    void lettersAndNumbersOfOtherScriptsAreNotSpecialButSymbolsAre() {
        // é is a letter and ٣ a number, so neither is special; they do not count as A-Z or 0-9 either.
        assertThat(PasswordPolicy.problemWith("Passwordé123")).isEqualTo("Add a special character.");
        assertThat(PasswordPolicy.problemWith("Password٣abc")).isEqualTo("Add a number and a special character.");
        assertThat(PasswordPolicy.problemWith("Password123£")).isNull();
        assertThat(PasswordPolicy.problemWith("Password123😀")).isNull();
        assertThat(PasswordPolicy.problemWith("Password123_")).isNull();
    }
}
