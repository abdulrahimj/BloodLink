package com.bloodlink.security;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Reports a refused start (Business Rule 15) as Spring Boot's "APPLICATION FAILED TO START" block
 * instead of a stack trace; registered in META-INF/spring.factories, so public.
 */
public class InsecureJwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<InsecureJwtSecretException> {

    /**
     * Picks the description for why the secret was refused; the action is the same fix each time,
     * with the placeholder getting its own explanation.
     */
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InsecureJwtSecretException cause) {
        return switch (cause.getReason()) {
            case MISSING -> weakSecret(cause,
                "The JWT signing secret (bloodlink.jwt.secret) is empty: BLOODLINK_JWT_SECRET is set, "
                    + "but to nothing.\n\n"
                    + "BloodLink cannot sign login tokens without a secret, and refuses to start.");
            case TOO_SHORT -> weakSecret(cause,
                "The JWT signing secret (bloodlink.jwt.secret) is " + cause.getSecretBytes()
                    + " bytes long. HMAC-SHA256 (HS256), which signs every login token, needs at least "
                    + JwtSecretPolicy.MIN_SECRET_BYTES + " bytes (256 bits).\n\n"
                    + "A shorter secret can be guessed, and anyone who guesses it can forge a login "
                    + "token for any account, including an administrator. BloodLink refuses to start "
                    + "rather than run like that.");
            case PUBLIC_PLACEHOLDER -> publicPlaceholder(cause);
        };
    }

    /** The report for a missing or too-short secret, under any profile. */
    private static FailureAnalysis weakSecret(InsecureJwtSecretException cause, String description) {
        String action = "Set the BLOODLINK_JWT_SECRET environment variable to a long, random, private "
            + "value of at least " + JwtSecretPolicy.MIN_SECRET_BYTES + " bytes, then start again. "
            + "48 random bytes in Base64 (64 characters) is plenty. To generate one:\n\n"
            + "    openssl rand -base64 48\n\n"
            + "or in PowerShell:\n\n"
            + "    $b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)\n\n"
            + "The README's \"Setting a real JWT secret\" section shows how to set it. Never commit "
            + "the real value. Changing it signs every user out.";
        return new FailureAnalysis(description, action, cause);
    }

    /** The report for the public placeholder under a profile that is not local-only. */
    private static FailureAnalysis publicPlaceholder(InsecureJwtSecretException cause) {
        String description = "The JWT signing secret (bloodlink.jwt.secret) is the public placeholder "
            + "value committed to the BloodLink repository, and the active profile(s) "
            + JwtSecretPolicy.describeProfiles(cause.getActiveProfiles())
            + " are not local-only.\n\n"
            + "Anyone who has read the repository knows this value, so anyone could forge a login "
            + "token for any account, including an administrator. BloodLink refuses to start rather "
            + "than run like that.";

        String action = "Set the BLOODLINK_JWT_SECRET environment variable to a long, random, private "
            + "value, then start again. One way to generate one:\n\n"
            + "    openssl rand -base64 48\n\n"
            + "Never commit the real value. The placeholder is only tolerated under the default "
            + "profile or 'demo', which run on a developer's own machine.";

        return new FailureAnalysis(description, action, cause);
    }
}
