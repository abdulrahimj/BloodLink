package com.bloodlink.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.bloodlink.web.error.InvalidPhoneNumberException;

/**
 * The format rule every phone number in the system passes through.
 *
 * These tests matter out of proportion to the size of the class: phone is the login
 * credential, so this function decides whether a real person can get into their own
 * account. The cases below are grouped as accept / convert / reject, and each
 * rejection case names the specific mistake it stands for — a number containing a
 * letter is a different failure from a number of the wrong length, and both were
 * previously accepted and stored.
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
        // Refused rather than stripped: a character that does not belong in a phone
        // number is far more likely a typo than a formatting choice, and "helpfully"
        // deleting it would store a number the person never gave us.
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
        // The case a per-character check alone would let through: every one of these
        // is digits and at most one leading plus, and none of them is a number this
        // system can store. Catching them here is what stops an unreachable account
        // being created — one that registered successfully and can never log in.
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
        // It is displayed verbatim under the phone input, so it has to tell someone
        // who has just been refused what to type instead — not merely that they were
        // wrong. Both accepted formats are named.
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
