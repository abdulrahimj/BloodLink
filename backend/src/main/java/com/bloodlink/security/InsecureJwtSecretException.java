package com.bloodlink.security;

/**
 * Thrown while the application is starting, when the JWT signing secret must not be
 * used (Business Rule 15, {@link JwtSecretPolicy}): it is missing, it is shorter than
 * HMAC-SHA256's 256-bit minimum, or it is the public placeholder and the active
 * profile is not a local-only one. It stops the start outright: there is no degraded
 * mode, because every token the application signed would be forgeable.
 *
 * {@link InsecureJwtSecretFailureAnalyzer} turns this into Spring Boot's plain
 * "APPLICATION FAILED TO START / Description / Action" report, so whoever is
 * deploying sees what is wrong and the one variable that fixes it, instead of a
 * bean-creation stack trace to dig through.
 *
 * <b>Never carries the secret itself</b>, only its length, so a refused start
 * cannot copy the value into a log.
 *
 * An IllegalStateException because that is what this is: the application's
 * configuration is in a state it must not run in.
 */
public class InsecureJwtSecretException extends IllegalStateException {

    /** Why the secret was refused. Each has its own explanation in the report. */
    public enum Reason {
        /** bloodlink.jwt.secret resolved to nothing, or only whitespace. */
        MISSING,
        /** Fewer than {@link JwtSecretPolicy#MIN_SECRET_BYTES} bytes. */
        TOO_SHORT,
        /** The committed placeholder, under a profile that is not local-only. */
        PUBLIC_PLACEHOLDER
    }

    /** How to make a real secret, shared by every message so they cannot drift. */
    static final String HOW_TO_GENERATE =
        "48 random bytes in Base64, for example the output of `openssl rand -base64 48`, "
            + "or in PowerShell: $b = New-Object byte[] 48; "
            + "[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); "
            + "[Convert]::ToBase64String($b)";

    private final Reason reason;
    private final int secretBytes;
    private final String[] activeProfiles;

    private InsecureJwtSecretException(Reason reason, int secretBytes, String[] activeProfiles, String message) {
        super(message);
        this.reason = reason;
        this.secretBytes = secretBytes;
        this.activeProfiles = activeProfiles.clone();
    }

    /** No secret at all — the variable was set to an empty value. */
    static InsecureJwtSecretException missing(String[] activeProfiles) {
        return new InsecureJwtSecretException(Reason.MISSING, 0, activeProfiles,
            "Refusing to start: bloodlink.jwt.secret is empty. Set the BLOODLINK_JWT_SECRET "
                + "environment variable to a long random value of at least "
                + JwtSecretPolicy.MIN_SECRET_BYTES + " bytes: " + HOW_TO_GENERATE + ".");
    }

    /** A secret shorter than HS256's 256-bit minimum. Reports the length, never the value. */
    static InsecureJwtSecretException tooShort(int secretBytes, String[] activeProfiles) {
        return new InsecureJwtSecretException(Reason.TOO_SHORT, secretBytes, activeProfiles,
            "Refusing to start: bloodlink.jwt.secret is " + secretBytes + " bytes long, but "
                + "HMAC-SHA256 (HS256) needs at least " + JwtSecretPolicy.MIN_SECRET_BYTES
                + " bytes (256 bits); a shorter key can be guessed, and with it a login token "
                + "forged for any account. Set the BLOODLINK_JWT_SECRET environment variable to "
                + "a long random value: " + HOW_TO_GENERATE + ".");
    }

    /** The committed placeholder under a profile that is not local-only. */
    static InsecureJwtSecretException publicPlaceholder(String[] activeProfiles) {
        return new InsecureJwtSecretException(Reason.PUBLIC_PLACEHOLDER, 0, activeProfiles,
            "Refusing to start: bloodlink.jwt.secret is the public placeholder committed to the "
                + "BloodLink repository, and the active profile(s) "
                + JwtSecretPolicy.describeProfiles(activeProfiles)
                + " are not local-only. Anyone who has read the repository could forge a login "
                + "token for any account, including an administrator. Set the BLOODLINK_JWT_SECRET "
                + "environment variable to a long random value before starting "
                + "(for example, the output of: openssl rand -base64 48). The placeholder is only "
                + "tolerated under the default profile or 'demo'.");
    }

    /** Why the secret was refused. */
    public Reason getReason() {
        return reason;
    }

    /** The refused secret's length in UTF-8 bytes; 0 unless the reason is TOO_SHORT. */
    public int getSecretBytes() {
        return secretBytes;
    }

    /** The profiles that were active when startup was refused. */
    public String[] getActiveProfiles() {
        return activeProfiles.clone();
    }
}
