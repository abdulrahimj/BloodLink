package com.bloodlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Business Rule 15, proven the way it will actually happen: by starting a Spring
 * Boot application and watching it refuse to start, or start and warn.
 *
 * <h2>How much of the application is started</h2>
 * A real {@code SpringApplication.run} — profiles resolved from
 * {@code --spring.profiles.active} exactly as a deployment passes them, the logging
 * system initialised, failure analyzers discovered from META-INF/spring.factories —
 * but with {@link JwtService} as its only bean and no auto-configuration. The check
 * lives entirely in that bean's constructor, so this is the whole of what decides
 * whether the application starts, and none of what would need a database. That is
 * what lets these run in the ordinary {@code mvnw test}, not only as an integration
 * test.
 *
 * <h2>Why every setting is a command-line argument</h2>
 * Command-line arguments outrank application.properties and environment variables.
 * Passing the secret and token lifetime that way means these tests prove the same
 * thing on every machine: on a fresh clone with no local application.properties, and
 * on a developer's machine that happens to have BLOODLINK_JWT_SECRET exported.
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtSecretStartupTest {

    private static final String PLACEHOLDER = JwtSecretPolicy.PUBLIC_PLACEHOLDER;
    private static final String REAL_SECRET = "Zm9yLXRlc3Rpbmctb25seS1ub3QtYS1yZWFsLWRlcGxveW1lbnQtc2VjcmV0";

    /** The unmistakable line of the startup warning. */
    private static final String WARNING_MARKER = "INSECURE JWT SECRET - LOCAL DEVELOPMENT ONLY";

    /**
     * Starts a minimal application whose only bean is JwtService.
     *
     * @param secret   the value of bloodlink.jwt.secret
     * @param profiles the value of spring.profiles.active, or null for the default profile
     * @param extra    any further command-line arguments
     */
    private static ConfigurableApplicationContext start(String secret, String profiles, String... extra) {
        List<String> args = new ArrayList<>(List.of(
            "--bloodlink.jwt.secret=" + secret,
            "--bloodlink.jwt.expiration-ms=3600000"
        ));
        if (profiles != null) {
            args.add("--spring.profiles.active=" + profiles);
        }
        args.addAll(List.of(extra));

        return new SpringApplicationBuilder(JwtService.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .registerShutdownHook(false)
            .run(args.toArray(String[]::new));
    }

    /** The exception at the bottom of a failed start's cause chain. */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ---------- refused: the placeholder outside the local-only profiles ----------

    @Test
    void theApplicationRefusesToStartOnThePlaceholderUnderAProdProfile(CapturedOutput output) {
        assertThatThrownBy(() -> start(PLACEHOLDER, "prod"))
            .satisfies(failure -> assertThat(rootCause(failure))
                .isInstanceOf(InsecureJwtSecretException.class)
                .hasMessageContaining("BLOODLINK_JWT_SECRET"));

        // Presented as Spring Boot's own startup-failure report, not a raw stack
        // trace: proves InsecureJwtSecretFailureAnalyzer is registered and used.
        assertThat(output.getOut() + output.getErr())
            .contains("APPLICATION FAILED TO START")
            .contains("public placeholder")
            .contains("Set the BLOODLINK_JWT_SECRET environment variable");
    }

    @Test
    void theApplicationRefusesToStartOnThePlaceholderUnderAStagingProfile() {
        assertThatThrownBy(() -> start(PLACEHOLDER, "staging"))
            .satisfies(failure -> assertThat(rootCause(failure)).isInstanceOf(InsecureJwtSecretException.class));
    }

    @Test
    void aProfileNobodyHasInventedYetIsRefusedToo() {
        // The future-proofing, end to end: nothing anywhere names this profile.
        assertThatThrownBy(() -> start(PLACEHOLDER, "prod-eu-west"))
            .satisfies(failure -> assertThat(rootCause(failure)).isInstanceOf(InsecureJwtSecretException.class));
    }

    @Test
    void demoAlongsideProdIsStillAProductionStartAndIsRefused() {
        assertThatThrownBy(() -> start(PLACEHOLDER, "demo,prod"))
            .satisfies(failure -> assertThat(rootCause(failure)).isInstanceOf(InsecureJwtSecretException.class));
    }

    @Test
    void theTestProfileGetsNoExemption() {
        // The command-line secret outranks application-test.properties' own test-only
        // secret, so this really is the placeholder under "test" — and it is refused.
        assertThatThrownBy(() -> start(PLACEHOLDER, "test"))
            .satisfies(failure -> assertThat(rootCause(failure)).isInstanceOf(InsecureJwtSecretException.class));
    }

    @Test
    void theRefusalHoldsEvenWithLazyInitialisationSwitchedOn() {
        // Lazy initialisation defers creating beans until first use. Were JwtService
        // lazy, the check would first run on the first login attempt, after the
        // deployment had already reported itself healthy. @Lazy(false) prevents that.
        assertThatThrownBy(() -> start(PLACEHOLDER, "prod", "--spring.main.lazy-initialization=true"))
            .satisfies(failure -> assertThat(rootCause(failure)).isInstanceOf(InsecureJwtSecretException.class));
    }

    // ---------- refused everywhere: a missing or short secret ----------

    @Test
    void aSecretShorterThan32BytesIsRefusedEvenUnderTheDefaultProfile(CapturedOutput output) {
        // The length rule has no local exemption. Proven under the default profile,
        // the one `mvnw spring-boot:run` uses, since that is where a short secret
        // typed in by hand is most likely to turn up.
        String shortSecret = "only-31-bytes-long-not-enough!!";

        assertThatThrownBy(() -> start(shortSecret, null))
            .satisfies(failure -> assertThat(rootCause(failure))
                .isInstanceOf(InsecureJwtSecretException.class)
                .hasMessageContaining("31 bytes long"));

        String log = output.getOut() + output.getErr();
        assertThat(log)
            .contains("APPLICATION FAILED TO START")
            .contains("is 31 bytes long")
            .contains("needs at least 32 bytes (256 bits)")
            .contains("Set the BLOODLINK_JWT_SECRET environment variable")
            .contains("RandomNumberGenerator");
        // The report names the length, never the value.
        assertThat(log).doesNotContain(shortSecret);
    }

    @Test
    void anEmptySecretIsRefusedUnderTheDemoProfile(CapturedOutput output) {
        // BLOODLINK_JWT_SECRET= (set, but to nothing) overrides the placeholder default
        // with "", so even a local profile has no key to sign with.
        assertThatThrownBy(() -> start("", "demo"))
            .satisfies(failure -> assertThat(rootCause(failure))
                .isInstanceOf(InsecureJwtSecretException.class)
                .hasMessageContaining("empty"));

        assertThat(output.getOut() + output.getErr())
            .contains("APPLICATION FAILED TO START")
            .contains("is empty");
    }

    @Test
    void exactly32BytesStartsUnderProd() {
        try (ConfigurableApplicationContext context = start("k".repeat(32), "prod")) {
            assertThat(context.getBean(JwtService.class)).isNotNull();
        }
    }

    // ---------- tolerated: the placeholder on a local-only profile, loudly ----------

    @Test
    void theDefaultProfileStartsOnThePlaceholderAndWarns(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(PLACEHOLDER, null)) {
            assertThat(context.getBean(JwtService.class)).isNotNull();
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
        }

        assertThat(output.getOut())
            .contains("WARN")
            .contains(WARNING_MARKER)
            .contains("PUBLIC PLACEHOLDER")
            .contains("NEVER run this configuration anywhere but your")
            .contains("BLOODLINK_JWT_SECRET");
    }

    @Test
    void theWarningIsAnEvenFrameThatReadsAsDeliberate(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(PLACEHOLDER, null)) {
            assertThat(context.getBean(JwtService.class)).isNotNull();
        }

        // Every line of the frame the same width: a ragged border reads as a glitch
        // in the log, and a warning that looks like a glitch gets skipped.
        List<Integer> frameWidths = output.getOut().lines()
            .map(String::stripTrailing)
            .filter(line -> line.startsWith("*"))
            .map(String::length)
            .distinct()
            .toList();

        assertThat(frameWidths).containsExactly(72);
    }

    @Test
    void theDemoProfileStartsOnThePlaceholderAndWarns(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(PLACEHOLDER, "demo")) {
            assertThat(context.getBean(JwtService.class)).isNotNull();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("demo");
        }

        assertThat(output.getOut()).contains(WARNING_MARKER);
    }

    // ---------- a real secret: no permission needed, nothing to say ----------

    @Test
    void aRealSecretStartsUnderProdWithNoWarning(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(REAL_SECRET, "prod")) {
            assertThat(context.getBean(JwtService.class)).isNotNull();
        }

        // A warning that also fires on a correctly configured deployment would teach
        // people to ignore it — so it must appear only when it is true.
        assertThat(output.getOut()).doesNotContain(WARNING_MARKER);
    }

    @Test
    void aRealSecretStartsUnderTheDemoProfileWithNoWarning(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(REAL_SECRET, "demo")) {
            assertThat(context.getBean(JwtService.class)).isNotNull();
        }

        assertThat(output.getOut()).doesNotContain(WARNING_MARKER);
    }
}
