package com.bloodlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bloodlink.security.JwtSecretPolicy.Verdict;

/**
 * Business Rule 15's decision tested exhaustively without starting anything (JwtSecretStartupTest does it end to end),
 * plus that the placeholder still matches the committed configuration files exactly.
 */
class JwtSecretPolicyTest {

    private static final String PLACEHOLDER = JwtSecretPolicy.PUBLIC_PLACEHOLDER;
    private static final String REAL_SECRET = "Zm9yLXRlc3Rpbmctb25seS1ub3QtYS1yZWFsLWRlcGxveW1lbnQtc2VjcmV0";

    private static String[] profiles(String... names) {
        return names;
    }

    // ---------- a real secret ----------

    @ParameterizedTest(name = "a real secret is accepted silently under profiles \"{0}\"")
    @ValueSource(strings = { "", "demo", "prod", "staging", "test", "demo,prod" })
    void aRealSecretIsAcceptedUnderEveryProfile(String profileList) {
        String[] active = profileList.isEmpty() ? profiles() : profileList.split(",");

        // The rule is about the placeholder only. A deployment that set its own
        // secret needs no permission from any profile, and gets no warning.
        assertThat(JwtSecretPolicy.evaluate(REAL_SECRET, active)).isEqualTo(Verdict.ACCEPTED);
    }

    // ---------- the placeholder, where it is tolerated ----------

    @Test
    void thePlaceholderIsToleratedWithAWarningUnderTheDefaultProfile() {
        // No profile active at all: mvnw spring-boot:run on a developer's machine.
        assertThat(JwtSecretPolicy.evaluate(PLACEHOLDER, profiles())).isEqualTo(Verdict.ACCEPTED_WITH_WARNING);
    }

    @Test
    void thePlaceholderIsToleratedWithAWarningWhenDefaultIsNamedExplicitly() {
        // Spring reports the default profile as no profiles, unless someone sets
        // spring.profiles.active=default by name — which means the same thing.
        assertThat(JwtSecretPolicy.evaluate(PLACEHOLDER, profiles("default"))).isEqualTo(Verdict.ACCEPTED_WITH_WARNING);
    }

    @Test
    void thePlaceholderIsToleratedWithAWarningUnderTheDemoProfile() {
        // docker-compose.yml's profile: a container-only database of fictional data.
        assertThat(JwtSecretPolicy.evaluate(PLACEHOLDER, profiles("demo"))).isEqualTo(Verdict.ACCEPTED_WITH_WARNING);
    }

    // ---------- the placeholder, everywhere else ----------

    @ParameterizedTest(name = "the placeholder is refused under profile \"{0}\"")
    @ValueSource(strings = { "prod", "production", "staging", "live", "prod-eu", "anything-added-later" })
    void thePlaceholderIsRefusedUnderAnyProfileNotKnownToBeLocal(String profile) {
        // The future-proofing: none of these names appear anywhere in the code, and
        // none needs to. An unlisted profile is refused by default.
        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(PLACEHOLDER, profiles(profile)))
            .isInstanceOf(InsecureJwtSecretException.class)
            .hasMessageContaining("BLOODLINK_JWT_SECRET")
            .hasMessageContaining(profile);
    }

    @Test
    void thePlaceholderIsRefusedUnderTheTestProfile() {
        // "test" has its own test-only secret rather than an exemption, since its file is never in the jar.
        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(PLACEHOLDER, profiles("test")))
            .isInstanceOf(InsecureJwtSecretException.class);
    }

    @Test
    void aLocalProfileAlongsideARealOneDoesNotMakeTheStartLocal() {
        // "demo,prod" is a production start that happens to carry a demo profile.
        // Every active profile must be local-only, not just one of them.
        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(PLACEHOLDER, profiles("demo", "prod")))
            .isInstanceOf(InsecureJwtSecretException.class);
        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(PLACEHOLDER, profiles("default", "staging")))
            .isInstanceOf(InsecureJwtSecretException.class);
    }

    @Test
    void profileNamesAreMatchedExactlyNotLoosely() {
        // Spring profile names are case-sensitive, so "Demo" is a different profile
        // from "demo" — one nobody declared local. Refusing it is the safe reading.
        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(PLACEHOLDER, profiles("Demo")))
            .isInstanceOf(InsecureJwtSecretException.class);
    }

    @ParameterizedTest(name = "the placeholder with surrounding whitespace is still recognised: [{0}]")
    @ValueSource(strings = { " ", "\n", "\t", "  \r\n" })
    void surroundingWhitespaceDoesNotDisguiseThePlaceholder(String padding) {
        // A trailing newline picked up from an environment file must not turn the
        // known public value into a "different" secret that slips past the check.
        String padded = padding + PLACEHOLDER + padding;

        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(padded, profiles("prod")))
            .isInstanceOf(InsecureJwtSecretException.class);
    }

    @Test
    void theRefusalNamesTheProblemAndTheFix() {
        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(PLACEHOLDER, profiles("prod")))
            .hasMessageContaining("Refusing to start")
            .hasMessageContaining("bloodlink.jwt.secret")
            .hasMessageContaining("public placeholder")
            .hasMessageContaining("BLOODLINK_JWT_SECRET")
            .hasMessageContaining("[prod]");
    }

    // ---------- a missing or short secret, refused everywhere ----------

    @ParameterizedTest(name = "an empty secret is refused under profiles \"{0}\"")
    @ValueSource(strings = { "", "default", "demo", "prod" })
    void anEmptySecretIsRefusedUnderEveryProfile(String profileList) {
        // BLOODLINK_JWT_SECRET set to nothing resolves to "", not to the placeholder.
        // No profile makes "no key at all" acceptable, local ones included.
        String[] active = profileList.isEmpty() ? profiles() : profileList.split(",");

        for (String empty : new String[] { null, "", "   ", "\n" }) {
            assertThatThrownBy(() -> JwtSecretPolicy.evaluate(empty, active))
                .isInstanceOf(InsecureJwtSecretException.class)
                .hasMessageContaining("empty")
                .hasMessageContaining("BLOODLINK_JWT_SECRET")
                .satisfies(e -> assertThat(((InsecureJwtSecretException) e).getReason())
                    .isEqualTo(InsecureJwtSecretException.Reason.MISSING));
        }
    }

    @ParameterizedTest(name = "a 31-byte secret is refused under profiles \"{0}\"")
    @ValueSource(strings = { "", "default", "demo", "prod", "test" })
    void aSecretShorterThan32BytesIsRefusedUnderEveryProfile(String profileList) {
        // One byte under HS256's 256-bit minimum. A short key is weak wherever it
        // runs, so unlike the placeholder rule, a local profile does not excuse it.
        String[] active = profileList.isEmpty() ? profiles() : profileList.split(",");
        String shortSecret = "x".repeat(JwtSecretPolicy.MIN_SECRET_BYTES - 1);

        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(shortSecret, active))
            .isInstanceOf(InsecureJwtSecretException.class)
            .hasMessageContaining("31 bytes long")
            .hasMessageContaining("at least 32 bytes (256 bits)")
            .hasMessageContaining("BLOODLINK_JWT_SECRET")
            .satisfies(e -> {
                InsecureJwtSecretException refusal = (InsecureJwtSecretException) e;
                assertThat(refusal.getReason()).isEqualTo(InsecureJwtSecretException.Reason.TOO_SHORT);
                assertThat(refusal.getSecretBytes()).isEqualTo(31);
            });
    }

    @Test
    void exactly32BytesIsEnough() {
        // The boundary itself: 256 bits is the minimum, so it is accepted.
        String secret = "y".repeat(JwtSecretPolicy.MIN_SECRET_BYTES);

        assertThat(JwtSecretPolicy.evaluate(secret, profiles("prod"))).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    void theLengthIsMeasuredInBytesNotCharacters() {
        // Bytes are what become the key. 16 "é" are 16 characters but 32 UTF-8 bytes;
        // 31 plain letters are 31 characters and 31 bytes.
        assertThat(JwtSecretPolicy.evaluate("é".repeat(16), profiles("prod"))).isEqualTo(Verdict.ACCEPTED);
        assertThatThrownBy(() -> JwtSecretPolicy.evaluate("z".repeat(31), profiles("prod")))
            .isInstanceOf(InsecureJwtSecretException.class);
    }

    @Test
    void surroundingWhitespaceDoesNotPadAShortSecretPastTheMinimum() {
        // 20 real characters plus 20 spaces of padding is still a 20-byte secret.
        String padded = " ".repeat(10) + "a".repeat(20) + " ".repeat(10);

        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(padded, profiles("prod")))
            .isInstanceOf(InsecureJwtSecretException.class)
            .hasMessageContaining("20 bytes long");
    }

    @Test
    void aRefusalNeverRepeatsTheSecretItself() {
        // The message reaches the startup log. It may name the length, never the value.
        String shortSecret = "SuperSecretShortValue123";

        assertThatThrownBy(() -> JwtSecretPolicy.evaluate(shortSecret, profiles("prod")))
            .isInstanceOf(InsecureJwtSecretException.class)
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortSecret));
    }

    @Test
    void thePlaceholderItselfIsLongEnoughSoLocalDevelopmentKeepsStarting() {
        // The length rule runs first everywhere, so a placeholder shortened below the minimum would break local starts.
        assertThat(PLACEHOLDER.getBytes(StandardCharsets.UTF_8).length)
            .isGreaterThanOrEqualTo(JwtSecretPolicy.MIN_SECRET_BYTES);
    }

    @Test
    void theIntegrationTestSecretIsLongEnough() throws IOException {
        // The "test" profile supplies its own secret; it must pass the same minimum,
        // or every integration test would fail to start.
        String properties;
        try (InputStream in = getClass().getResourceAsStream("/application-test.properties")) {
            assertThat(in).as("application-test.properties on the test classpath").isNotNull();
            properties = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher line = Pattern.compile("^bloodlink\\.jwt\\.secret=(.*?)\\s*$", Pattern.MULTILINE).matcher(properties);

        assertThat(line.find()).as("bloodlink.jwt.secret line in application-test.properties").isTrue();
        assertThat(JwtSecretPolicy.evaluate(line.group(1), profiles("test"))).isEqualTo(Verdict.ACCEPTED);
    }

    // ---------- the placeholder the code looks for is the one the files contain ----------

    @Test
    void thePlaceholderMatchesTheDefaultInTheCommittedPropertiesTemplate() throws IOException {
        // If the default in application.properties.example drifts from the constant, the check would silently stop
        // recognising it; this fails the build instead.
        String template;
        try (InputStream in = getClass().getResourceAsStream("/application.properties.example")) {
            assertThat(in).as("application.properties.example on the classpath").isNotNull();
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher line = Pattern
            .compile("^bloodlink\\.jwt\\.secret=\\$\\{BLOODLINK_JWT_SECRET:(.*)}\\s*$", Pattern.MULTILINE)
            .matcher(template);

        assertThat(line.find()).as("bloodlink.jwt.secret line in the template").isTrue();
        assertThat(line.group(1)).isEqualTo(PLACEHOLDER);
    }

    @Test
    void thePlaceholderMatchesTheValueInTheCommittedEnvExample() throws IOException {
        // The same drift check for .env.example at the repository root (one level above the backend module).
        Path envExample = Path.of("..", ".env.example");
        assertThat(envExample).as(".env.example at the repository root").exists();

        Matcher line = Pattern
            .compile("^BLOODLINK_JWT_SECRET=(.*?)\\s*$", Pattern.MULTILINE)
            .matcher(Files.readString(envExample, StandardCharsets.UTF_8));

        assertThat(line.find()).as("BLOODLINK_JWT_SECRET line in .env.example").isTrue();
        assertThat(line.group(1)).isEqualTo(PLACEHOLDER);
    }
}
