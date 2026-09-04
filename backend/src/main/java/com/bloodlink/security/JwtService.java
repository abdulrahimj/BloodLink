package com.bloodlink.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
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
public class JwtService {

    /** Claim holding the Role enum name, e.g. "DONOR". Drives role-based access control. */
    private static final String CLAIM_ROLE = "role";
    /** Display-name claim, so the frontend can greet the user without a second call. */
    private static final String CLAIM_NAME = "fullName";

    private final SecretKey signingKey;
    private final long expiryMillis;

    JwtService(
        @Value("${bloodlink.jwt.secret}") String secret,
        @Value("${bloodlink.jwt.expiration-ms}") long expiryMillis
    ) {
        // HMAC-SHA256 requires at least a 256-bit key; a short secret would throw here
        // at startup rather than silently weakening every token we sign.
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
