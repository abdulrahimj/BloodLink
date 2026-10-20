package com.bloodlink.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.bloodlink.web.error.InvalidFieldException;

/**
 * A donor's email format (Business Rule 18), grouped as blank (no email), accepted and refused;
 * each refused case is a real registration-desk typo.
 */
class EmailAddressTest {

    // ---------- blank means "no email" ----------

    @Test
    void nullMeansNoEmail() {
        assertThat(EmailAddress.normalizeOptional(null)).isNull();
    }

    @ParameterizedTest(name = "\"{0}\" means no email")
    @ValueSource(strings = { "", " ", "   ", "\t", " \n " })
    void blankMeansNoEmailRatherThanAnEmptyString(String blank) {
        // Null, never "": two empty strings would collide on the unique index, where
        // any number of NULLs do not (V13).
        assertThat(EmailAddress.normalizeOptional(blank)).isNull();
    }

    // ---------- accepted ----------

    @ParameterizedTest(name = "\"{0}\" is stored as \"{1}\"")
    @CsvSource({
        "aminata@example.com,              aminata@example.com",
        "  Aminata.Sesay@Example.COM  ,    aminata.sesay@example.com",
        "a.sesay+donor@mail.example.org,   a.sesay+donor@mail.example.org",
        "first_last-99@gov.sl,             first_last-99@gov.sl",
        "donor@example.co.uk,              donor@example.co.uk",
        "x@sub-domain.example.africa,      x@sub-domain.example.africa"
    })
    void aRealAddressIsAcceptedTrimmedAndLowerCased(String typed, String stored) {
        // Lower-cased so Aminata@Example.com and aminata@example.com are one address
        // to the unique constraint.
        assertThat(EmailAddress.normalizeOptional(typed)).isEqualTo(stored);
    }

    // ---------- refused ----------

    @ParameterizedTest(name = "\"{0}\" is refused ({1})")
    @CsvSource({
        "name@gmail,                 no dot after the @ — the commonest typo",
        "name@gmail.c,               a one-letter ending",
        "name@gmail..com,            two dots in a row in the domain",
        "name..sesay@gmail.com,      two dots in a row in the name",
        ".name@gmail.com,            a name starting with a dot",
        "name.@gmail.com,            a name ending with a dot",
        "name@@gmail.com,            two @ signs",
        "namegmail.com,              no @ at all",
        "@gmail.com,                 nothing before the @",
        "name@,                      nothing after the @",
        "name@.com,                  a domain starting with a dot",
        "name@gmail.com.,            a trailing dot",
        "name@-gmail.com,            a domain label starting with a hyphen",
        "name@gmail-.com,            a domain label ending with a hyphen",
        "name sesay@gmail.com,       a space inside",
        "name@gmail.com2,            digits in the ending",
        "name(at)gmail.com,          characters that do not belong in an address"
    })
    void aMalformedAddressIsRefusedOnTheEmailField(String typed, String why) {
        assertThatThrownBy(() -> EmailAddress.normalizeOptional(typed))
            .describedAs(why)
            .isInstanceOf(InvalidFieldException.class)
            .satisfies(ex -> assertThat(((InvalidFieldException) ex).getField()).isEqualTo("email"))
            .hasMessageContaining("name@example.com");
    }

    @Test
    void aNameLongerThanTheStandardAllowsIsRefused() {
        String sixtyFive = "a".repeat(65);

        assertThatThrownBy(() -> EmailAddress.normalizeOptional(sixtyFive + "@example.com"))
            .isInstanceOf(InvalidFieldException.class);
        // 64 is the limit, and is fine.
        assertThat(EmailAddress.normalizeOptional("a".repeat(64) + "@example.com")).isNotNull();
    }

    @Test
    void anAddressLongerThanTheStandardAllowsIsRefused() {
        String longDomain = ("a".repeat(60) + ".").repeat(4) + "com"; // 247 characters
        assertThatThrownBy(() -> EmailAddress.normalizeOptional("abcdefgh@" + longDomain))
            .isInstanceOf(InvalidFieldException.class);
    }

    @Test
    void theRefusalSaysBlankIsAlsoAnOption() {
        // Said on the form, so a donor who has no real address knows they can simply
        // leave it out rather than inventing one.
        assertThatThrownBy(() -> EmailAddress.normalizeOptional("name@gmail"))
            .hasMessageContaining("leave it blank");
    }
}
