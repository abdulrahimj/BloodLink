package com.bloodlink.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.bloodlink.web.error.InvalidPhoneNumberException;

/**
 * The format rule every phone passes through, which decides whether a person can sign in.
 * Grouped as accept / convert / reject, each rejection naming the mistake it stands for.
 */
class PhoneNumberTest {

    /** What every accepted spelling of one number must come out as. */
    private static final String NORMALIZED = "+23276900005";

    // ---------- local format ----------

    @Test
    void aLocalNumberGainsTheCountryCodeAndLosesItsLeadingZero() {
        // The format a donor in Makeni would write their own number in.
        assertThat(PhoneNumber.normalize("076900005")).isEqualTo(NORMALIZED);
    }

    @Test
    void aLocalNumberWrittenWithSpacesIsAcceptedToo() {
        assertThat(PhoneNumber.normalize("076 900 005")).isEqualTo(NORMALIZED);
    }

    // ---------- international format ----------

    @Test
    void anInternationalNumberWithSpacesLosesThemAndKeepsItsCountryCode() {
        // The format the placeholder on both auth forms shows.
        assertThat(PhoneNumber.normalize("+232 76 900 005")).isEqualTo(NORMALIZED);
    }

    @Test
    void anAlreadyNormalisedNumberIsUnchanged() {
        // Idempotent: what comes out can be fed back in — which is what makes it
        // safe for login to normalise a value that was normalised at registration.
        assertThat(PhoneNumber.normalize(NORMALIZED)).isEqualTo(NORMALIZED);
    }

    @ParameterizedTest(name = "\"{0}\" normalises to +23276900005")
    @ValueSource(strings = {
        "076900005",           // local
        " 076900005 ",         // local, pasted with surrounding whitespace
        "076 900 005",         // local, spaced
        "+23276900005",        // international, unspaced
        "+232 76 900 005",     // international, spaced as the placeholder shows
        "+232 76900005",       // international, spaced once
        "+ 232 76 900 005"     // international, space after the plus
    })
    void everyAcceptedSpellingOfOneNumberProducesOneStoredValue(String typed) {
        // The whole point of the rule: these are one person's number written seven
        // ways, and all seven have to compare equal to what the database holds.
        assertThat(PhoneNumber.normalize(typed)).isEqualTo(NORMALIZED);
    }

    // ---------- rejected: characters that do not belong in a number ----------

    @ParameterizedTest(name = "\"{0}\" is refused ({1})")
    @CsvSource({
        "07690000a,       a letter at the end",
        "0769O0005,       a letter in the middle (capital O for zero)",
        "+232 76 900 00S, a letter in an international number",
        "076-900-005,     hyphens as separators",
        "(076) 900005,    brackets around the prefix",
        "+232.76900005,   a full stop",
        "+232+76900005,   a second plus sign",
        "232+76900005,    a plus that is not at the start",
        "076900005#,      a stray symbol"
    })
    void anythingOtherThanDigitsAndOneLeadingPlusIsRefused(String typed, String why) {
        // Refused rather than stripped: a stray character is more likely a typo than formatting.
        assertThatThrownBy(() -> PhoneNumber.normalize(typed))
            .describedAs(why)
            .isInstanceOf(InvalidPhoneNumberException.class)
            .hasMessageContaining("valid Sierra Leone phone number");
    }

    // ---------- rejected: right characters, wrong length or wrong country ----------

    @ParameterizedTest(name = "\"{0}\" is refused ({1})")
    @CsvSource({
        "07690000,          local, one digit short",
        "0769000055,        local, one digit too many",
        "0,                 a lone zero",
        "+2327690000,       international, two digits short",
        "+23276900005123,   international, far too long",
        "+232,              a country code with no number after it",
        "76900005,          no country code and no leading zero",
        "23276900005,       the country code without its plus",
        "+447700900005,     a valid number, but not a Sierra Leone one"
    })
    void aNumberOfTheWrongLengthIsRefusedEvenThoughEveryCharacterIsValid(String typed, String why) {
        // Valid characters but the wrong length or country: refused so no account is created that can never log in.
        assertThatThrownBy(() -> PhoneNumber.normalize(typed))
            .describedAs(why)
            .isInstanceOf(InvalidPhoneNumberException.class);
    }

    // ---------- rejected: nothing at all ----------

    @Test
    void nullIsRefusedRatherThanCrashing() {
        // The DTOs' @NotBlank catches this first on both auth endpoints; this is the
        // guarantee for any future caller that has no such annotation in front of it.
        assertThatThrownBy(() -> PhoneNumber.normalize(null))
            .isInstanceOf(InvalidPhoneNumberException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void anEmptyOrBlankNumberIsRefused(String typed) {
        assertThatThrownBy(() -> PhoneNumber.normalize(typed))
            .isInstanceOf(InvalidPhoneNumberException.class);
    }

    // ---------- the message ----------

    @Test
    void theRejectionMessageShowsBothAcceptedFormats() {
        // Shown under the phone input, so it must name both accepted formats.
        assertThatThrownBy(() -> PhoneNumber.normalize("12345"))
            .hasMessageContaining("076900005")
            .hasMessageContaining("+232 76 900 005");
    }

    // ---------- search fragments (never refused, unlike normalize) ----------

    @ParameterizedTest(name = "\"{0}\" searches for \"{1}\"")
    @CsvSource({
        "076900015,       +23276900015",   // full local form -> stored form
        "0769,            +232769",        // partial local form keeps the +232 mapping
        "'+232 769 000',  +232769000",     // international with spaces
        "076-900-015,     +23276900015",   // hyphens are noise too
        "900,             900",            // a bare fragment is matched as typed
        "23276900015,     23276900015"     // no +, still a substring of the stored form
    })
    void searchFragmentsMapOnlyTheLocalPrefixAndDropSpacesAndHyphens(String typed, String expected) {
        assertThat(PhoneNumber.searchFragment(typed)).isEqualTo(expected);
    }

    @Test
    void aSearchFragmentNeverThrowsForTextThatIsNotAPhone() {
        assertThat(PhoneNumber.searchFragment("Aminata")).isEqualTo("Aminata");
    }
}
