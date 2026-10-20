package com.bloodlink.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * Decides at startup whether the JWT secret is safe (Business Rule 15): missing or short is refused everywhere,
 * and the public placeholder only allowed on the listed local-only profiles. Free of Spring types for testing.
 */
final class JwtSecretPolicy {

    /**
     * The development default in application.properties(.example) and .env.example;
     * JwtSecretPolicyTest fails the build if they drift.
     */
    static final String PUBLIC_PLACEHOLDER = "local-dev-only-change-me-0123456789abcdef";

    /**
     * The shortest secret accepted: 32 UTF-8 bytes, the 256 bits HS256 requires;
     * checked here so a short key gets a readable startup report.
     */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * Profiles that never leave a developer's machine and may use the placeholder: default and demo.
     * Deliberately not test, which sets its own test-only secret.
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
     * Applies the rule in order (missing, too short, placeholder) to the whitespace-stripped secret;
     * throws InsecureJwtSecretException without ever including the secret.
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
