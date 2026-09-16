package com.bloodlink.security;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Presents a refused start (Business Rule 15) the way Spring Boot presents its own
 * configuration mistakes:
 *
 * <pre>
 * ***************************
 * APPLICATION FAILED TO START
 * ***************************
 *
 * Description:
 * ...what is wrong...
 *
 * Action:
 * ...what to do about it...
 * </pre>
 *
 * Without this, the same failure still stops the application — the exception is
 * what does that — but it reaches the log as a BeanCreationException wrapped
 * around the real cause, several screens of stack trace above the one line that
 * matters. The person reading it is deploying the system, possibly not the person
 * who wrote it, and should not have to know which bean to look for.
 *
 * Registered in META-INF/spring.factories, which is how Spring Boot discovers
 * failure analyzers; it has no effect on a start that succeeds. Public because it is
 * instantiated reflectively from that file, from outside this package.
 */
public class InsecureJwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<InsecureJwtSecretException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InsecureJwtSecretException cause) {
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
