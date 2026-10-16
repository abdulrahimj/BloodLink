package com.bloodlink.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * Decides, once at startup, whether the configured JWT signing secret is safe to run
 * with — Business Rule 15.
 *
 * <h2>The problem this closes</h2>
 * application.properties reads the secret as
 * {@code ${BLOODLINK_JWT_SECRET:local-dev-only-change-me-0123456789abcdef}}: the
 * environment variable if it is set, otherwise that placeholder. The placeholder is
 * committed to a public repository, so anyone can read it — and anyone who knows the
 * signing secret can mint a token for any account, administrators included. The
 * only check that used to exist was the 32-character minimum HMAC-SHA256 imposes,
 * and the placeholder is 41 characters. So a real deployment that simply forgot to
 * set the variable started normally, printed nothing unusual, and accepted forged
 * logins.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li><b>A missing or short secret</b>, meaning empty or fewer than
 *       {@link #MIN_SECRET_BYTES} bytes, is refused under every profile, local ones
 *       included. HMAC-SHA256 (HS256) needs a key of at least 256 bits; a shorter
 *       one is guessable, and no profile makes a guessable key acceptable.</li>
 *   <li><b>A real secret</b> (long enough, and not the placeholder) is accepted
 *       under every profile, silently.</li>
 *   <li><b>The placeholder under a local-only profile</b> is accepted, with a loud
 *       warning — see {@link #LOCAL_ONLY_PROFILES}.</li>
 *   <li><b>The placeholder under any other profile</b> — {@code prod},
 *       {@code staging}, anything added later, or a mix such as
 *       {@code demo,prod} — is refused, and the application does not start.</li>
 * </ul>
 * The exemption is a list of profiles known to be safe, never a list of profiles
 * known to be dangerous. That is the whole of the future-proofing: nobody has to
 * remember to add a new "prod-eu" profile anywhere for it to be protected, because
 * a profile nobody listed is refused by default.
 *
 * Kept free of Spring types (it takes the active profiles as plain strings) so the
 * decision can be tested exhaustively without starting anything.
 */
final class JwtSecretPolicy {

    /**
     * The development default written into application.properties(.example) and
     * .env.example. Must match those files character for character, or this check
     * silently stops recognising it — {@code JwtSecretPolicyTest} reads both files
     * and fails the build if they ever drift apart.
     */
    static final String PUBLIC_PLACEHOLDER = "local-dev-only-change-me-0123456789abcdef";

    /**
     * The shortest secret accepted: 32 bytes, the 256 bits HMAC-SHA256 requires of
     * its key (RFC 7518, section 3.2). Measured in UTF-8 bytes, not characters,
     * because bytes are what {@code JwtService}'s constructor turns into the key.
     *
     * The JWT library refuses a shorter key too, but only as a bare
     * {@code WeakKeyException} inside a bean-creation stack trace. Checking here first
     * turns the same refusal into Spring Boot's plain "APPLICATION FAILED TO START"
     * report, naming the length found and the variable that fixes it.
     *
     * The placeholder is 41 bytes, so local development is unaffected. A secret made
     * the way the README describes (48 random bytes in Base64) is 64.
     */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * Profiles that never leave a developer's own machine, and so may run on the
     * public placeholder. Checked against the profiles this codebase actually has:
     * <ul>
     *   <li>{@code default} — no profile active: {@code mvnw spring-boot:run} against
     *       a local bloodlink_dev. Spring reports this as no active profiles at all,
     *       and as {@code "default"} only if someone names it explicitly, so both
     *       forms are covered.</li>
     *   <li>{@code demo} — application-demo.properties, run by docker-compose.yml
     *       against a container-only bloodlink_demo seeded with fictional data.</li>
     * </ul>
     * Deliberately <b>not</b> {@code test}. That profile exists only as
     * {@code @ActiveProfiles("test")} on the integration tests, and its properties
     * file lives in src/test/resources, which is never packaged into the jar. It
     * sets its own test-only secret instead of needing an exemption — so a server
     * accidentally started with {@code SPRING_PROFILES_ACTIVE=test}, where no test
     * properties file exists to supply anything, is refused like any other.
     */
    static final Set<String> LOCAL_ONLY_PROFILES = Set.of("default", "demo");

    /** What startup should do once the secret has passed. */
    enum Verdict {
        /** A real secret. Nothing to say. */
        ACCEPTED,
        /** The placeholder, on a local-only profile. Start, but warn loudly. */
        ACCEPTED_WITH_WARNING
    }

    private JwtSecretPolicy() {
        // Utility class.
    }

    /**
     * Applies the rule above, in this order: missing, too short, then placeholder.
     *
     * Compared after stripping surrounding whitespace, so a stray space or newline
     * picked up from an environment file can neither turn the known placeholder into
     * a "different" secret that slips past this check, nor pad a short secret past
     * the length minimum.
     *
     * Nothing thrown here ever contains the secret itself, only its length, so a
     * refused start cannot leak the value into a log.
     *
     * @param secret         the resolved value of bloodlink.jwt.secret
     * @param activeProfiles Spring's active profiles; empty means the default profile
     * @return whether to start silently or with a warning
     * @throws InsecureJwtSecretException if the secret is missing or shorter than
     *         {@link #MIN_SECRET_BYTES} under any profile, or is the placeholder
     *         under any profile not listed in {@link #LOCAL_ONLY_PROFILES}
     */
    static Verdict evaluate(String secret, String[] activeProfiles) {
        String stripped = secret == null ? "" : secret.strip();

        // Checked under every profile, before the placeholder rule: a short key is
        // weak wherever it runs, so a local profile does not excuse it.
        if (stripped.isEmpty()) {
            throw InsecureJwtSecretException.missing(activeProfiles);
        }
        int bytes = stripped.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw InsecureJwtSecretException.tooShort(bytes, activeProfiles);
        }

        if (!PUBLIC_PLACEHOLDER.equals(stripped)) {
            return Verdict.ACCEPTED;
        }

        // Every active profile must be local-only, not merely one of them: "demo,prod"
        // is a production start that happens to carry a demo profile too.
        boolean allLocalOnly = Arrays.stream(activeProfiles).allMatch(LOCAL_ONLY_PROFILES::contains);
        if (!allLocalOnly) {
            throw InsecureJwtSecretException.publicPlaceholder(activeProfiles);
        }
        return Verdict.ACCEPTED_WITH_WARNING;
    }

    /** How the active profiles read in a message: "[prod, staging]", or the default. */
    static String describeProfiles(String[] activeProfiles) {
        return activeProfiles.length == 0
            ? "none (the default profile)"
            : Arrays.toString(activeProfiles);
    }
}
