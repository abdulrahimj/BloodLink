package com.bloodlink.security;

/**
 * Thrown while the application is starting, when the JWT signing secret is still the
 * public placeholder and the active profile is not a local-only one (Business Rule
 * 15, {@link JwtSecretPolicy}). It stops the start outright: there is no degraded
 * mode, because every token the application signed would be forgeable by anyone who
 * has read the repository.
 *
 * {@link InsecureJwtSecretFailureAnalyzer} turns this into Spring Boot's plain
 * "APPLICATION FAILED TO START / Description / Action" report, so whoever is
 * deploying sees what is wrong and the one variable that fixes it, instead of a
 * bean-creation stack trace to dig through.
 *
 * An IllegalStateException because that is what this is: the application's
 * configuration is in a state it must not run in.
 */
public class InsecureJwtSecretException extends IllegalStateException {

    private final String[] activeProfiles;

    InsecureJwtSecretException(String[] activeProfiles) {
        super(
            "Refusing to start: bloodlink.jwt.secret is the public placeholder committed to the "
                + "BloodLink repository, and the active profile(s) "
                + JwtSecretPolicy.describeProfiles(activeProfiles)
                + " are not local-only. Anyone who has read the repository could forge a login "
                + "token for any account, including an administrator. Set the BLOODLINK_JWT_SECRET "
                + "environment variable to a long random value before starting "
                + "(for example, the output of: openssl rand -base64 48). The placeholder is only "
                + "tolerated under the default profile or 'demo'."
        );
        this.activeProfiles = activeProfiles.clone();
    }

    /** The profiles that were active when startup was refused. */
    public String[] getActiveProfiles() {
        return activeProfiles.clone();
    }
}
