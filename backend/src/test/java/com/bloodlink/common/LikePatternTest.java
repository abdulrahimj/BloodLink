package com.bloodlink.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The search-text escaping shared by the Donors roster and the User Accounts table:
 * what a person types must be matched literally, so LIKE's own wildcards are escaped.
 */
class LikePatternTest {

    @Test
    void plainTextIsWrappedToMatchAnywhere() {
        assertThat(LikePattern.contains("kamara")).isEqualTo("%kamara%");
    }

    @Test
    void percentIsEscapedSoItNoLongerMatchesEverything() {
        assertThat(LikePattern.contains("%")).isEqualTo("%\\%%");
    }

    @Test
    void underscoreIsEscapedSoItNoLongerMatchesAnyOneCharacter() {
        assertThat(LikePattern.contains("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    void aTypedBackslashIsEscapedFirstSoItStaysLiteralToo() {
        assertThat(LikePattern.escape("a\\%")).isEqualTo("a\\\\\\%");
    }
}
