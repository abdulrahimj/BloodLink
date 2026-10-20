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
 * Issues and verifies the stateless tokens: subject (user id), role and name, so no session is needed.
 * A role change takes effect only when the token expires.
 */
@Service
// Never lazily created, so the secret check in the constructor always runs at startup.
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
     * Printed at WARN when the placeholder is used on a local profile, framed and upper-case
     * so it stands out near the top of the startup log.
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
     * Frames lines in asterisks, padding each so the right border lines up.
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
     * Builds the signing key after JwtSecretPolicy approves the secret (Business Rule 15), so no path can sign
     * with the placeholder in production. The key is the secret's raw UTF-8 bytes (Base64 is not decoded).
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
     * Verifies an incoming token's signature and expiry and returns its claims;
     * any problem is a JwtException, treated as not authenticated.
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
