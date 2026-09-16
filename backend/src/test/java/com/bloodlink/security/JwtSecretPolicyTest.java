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
 * The decision behind Business Rule 15, tested exhaustively without starting
 * anything: which secrets and which profiles may run, and which must not.
 *
 * {@code JwtSecretStartupTest} proves the same rule end to end, through a real
 * Spring Boot start. This class exists for the cases too numerous to start an
 * application for each, and for the one check no startup test would ever notice:
 * that the placeholder this code looks for is still, character for character, the
 * one the committed configuration files actually contain.
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
        // "test" has its own test-only secret in src/test/resources rather than an
        // exemption — that file is never in the jar, so a server started with this
        // profile name by mistake would otherwise run on the public placeholder.
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

    // ---------- the placeholder the code looks for is the one the files contain ----------

    @Test
    void thePlaceholderMatchesTheDefaultInTheCommittedPropertiesTemplate() throws IOException {
        // If someone edits the default in application.properties.example and not
        // the constant, this check would silently stop recognising the placeholder
        // — every start would look like "a real secret" and pass. This test is
        // what turns that silent failure into a failed build.
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
        // The same drift, for the file docker-compose users copy. Read from the
        // repository root: Maven runs tests with the backend module as the working
        // directory, and .env.example sits one level above it.
        Path envExample = Path.of("..", ".env.example");
        assertThat(envExample).as(".env.example at the repository root").exists();

        Matcher line = Pattern
            .compile("^BLOODLINK_JWT_SECRET=(.*?)\\s*$", Pattern.MULTILINE)
            .matcher(Files.readString(envExample, StandardCharsets.UTF_8));

        assertThat(line.find()).as("BLOODLINK_JWT_SECRET line in .env.example").isTrue();
        assertThat(line.group(1)).isEqualTo(PLACEHOLDER);
    }
}
