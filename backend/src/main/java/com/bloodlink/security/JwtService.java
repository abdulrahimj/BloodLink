package com.bloodlink.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.bloodlink.domain.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies the stateless access tokens used across the API.
 *
 * The token is the only thing the server knows about a caller between requests —
 * there is no session — so it carries everything the security layer needs to
 * rebuild an Authentication: the user id (subject), their role, and their name.
 * Role travels inside the signed payload rather than being re-read from the
 * database on every request, which keeps request handling to a single round trip;
 * the trade-off is that a role change only takes effect once the token expires.
 */
@Service
// Never lazily created, even if spring.main.lazy-initialization is ever switched on
// for faster startup. The secret check in the constructor is only a startup check
// if this bean is built at startup; lazily, it would first run on the first login
// attempt, long after a deployment had been declared healthy.
@Lazy(false)
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Claim holding the Role enum name, e.g. "DONOR". Drives role-based access control. */
    private static final String CLAIM_ROLE = "role";
    /** Display-name claim, so the frontend can greet the user without a second call. */
    private static final String CLAIM_NAME = "fullName";

    /** Width of the warning frame, border characters included. Declared before the
     *  banner that uses it, so the order of initialisation reads as it runs. */
    private static final int BANNER_WIDTH = 72;

    /**
     * Printed at WARN when the public placeholder is in use on a local-only profile.
     * Framed and upper-case on purpose: it has to survive being scrolled past in a
     * startup log full of Flyway and Hibernate lines, which is exactly what a quiet
     * one-line warning would not. It prints before the database pool opens, so it
     * sits near the top of the log rather than buried under the migration output.
     */
    private static final String PLACEHOLDER_WARNING = banner(
        "INSECURE JWT SECRET - LOCAL DEVELOPMENT ONLY",
        "",
        "bloodlink.jwt.secret is the PUBLIC PLACEHOLDER committed to the",
        "BloodLink repository. Anyone who has read it can forge a login",
        "token for any account, including an administrator.",
        "",
        "Tolerated only because the active profile is local-only",
        "(default or demo). NEVER run this configuration anywhere but your",
        "own machine. Set BLOODLINK_JWT_SECRET to a long random value."
    );

    /**
     * Frames lines of text in asterisks, padding each to the same width so the
     * right-hand border lines up. Computed rather than hand-typed: a hand-aligned
     * frame goes ragged the first time anyone edits a word inside it.
     */
    private static String banner(String... lines) {
        String border = "*".repeat(BANNER_WIDTH);
        int textWidth = BANNER_WIDTH - 6; // "*  " on the left, "  *" on the right
        StringBuilder out = new StringBuilder("\n").append(border).append('\n');
        for (String line : lines) {
            out.append("*  ").append(String.format("%-" + textWidth + "s", line)).append("  *\n");
        }
        return out.append(border).toString();
    }

    private final SecretKey signingKey;
    private final long expiryMillis;

    /**
     * Builds the signing key — after deciding whether the secret is safe to build it
     * from (Business Rule 15, {@link JwtSecretPolicy}).
     *
     * The check lives here, in the one place the secret is turned into a key, rather
     * than in a separate startup hook. That way no path can sign a token with the
     * placeholder on a real deployment: if this constructor refuses, there is no
     * JwtService, so there is no application.
     *
     * Two things can stop the start here, in this order:
     *   1. The placeholder under a profile that is not local-only —
     *      InsecureJwtSecretException, presented by InsecureJwtSecretFailureAnalyzer.
     *   2. A secret shorter than HMAC-SHA256's 256-bit minimum — Keys.hmacShaKeyFor
     *      throws rather than silently weakening every token we sign.
     */
    JwtService(
        @Value("${bloodlink.jwt.secret}") String secret,
        @Value("${bloodlink.jwt.expiration-ms}") long expiryMillis,
        Environment environment
    ) {
        if (JwtSecretPolicy.evaluate(secret, environment.getActiveProfiles())
                == JwtSecretPolicy.Verdict.ACCEPTED_WITH_WARNING) {
            log.warn(PLACEHOLDER_WARNING);
        }

        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryMillis = expiryMillis;
    }

    /**
     * Mints a signed token for a user who has just proven their password.
     * Called only from AuthService after authentication succeeds.
     */
    public String issueToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.getId().toString())
            .claim(CLAIM_ROLE, user.getRole().name())
            .claim(CLAIM_NAME, user.getFullName())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(expiryMillis)))
            .signWith(signingKey)
            .compact();
    }

    /**
     * Verifies the signature and expiry of an incoming token and returns its claims.
     * Any tampering, wrong signing key, or expiry surfaces as JwtException, which the
     * filter treats as "not authenticated" rather than as a server error.
     */
    public Claims parseClaims(String token) throws JwtException {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /** The authenticated user's id, taken from the token's subject. */
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /** The authenticated user's role name, used to build the granted authority. */
    public String extractRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    /** Seconds until the issued token expires — returned to the client alongside it. */
    public long getExpirySeconds() {
        return expiryMillis / 1000;
    }
}
