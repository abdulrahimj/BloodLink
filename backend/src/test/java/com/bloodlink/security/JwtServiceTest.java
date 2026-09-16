package com.bloodlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;

import io.jsonwebtoken.JwtException;

/**
 * Covers the token contract the whole security layer rests on: what goes into a
 * token comes back out, and a token we did not sign is refused.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac-sha256";
    private static final long ONE_HOUR_MS = 3_600_000L;

    /** No active profiles — the default profile. Irrelevant to these tests, whose
     *  secret is not the placeholder; the profile rules are JwtSecretPolicyTest's job. */
    private static final MockEnvironment ENVIRONMENT = new MockEnvironment();

    private final JwtService jwtService = new JwtService(SECRET, ONE_HOUR_MS, ENVIRONMENT);

    private static User donor() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Aminata Kamara");
        user.setEmail("aminata@example.com");
        user.setPhone("+232 76 000 001");
        user.setPasswordHash("irrelevant-here");
        user.setRole(Role.DONOR);
        return user;
    }

    @Test
    void issuedTokenCarriesUserIdAndRole() {
        User user = donor();

        var claims = jwtService.parseClaims(jwtService.issueToken(user));

        // The filter rebuilds the principal from exactly these two claims.
        assertThat(jwtService.extractUserId(claims)).isEqualTo(user.getId());
        assertThat(jwtService.extractRole(claims)).isEqualTo("DONOR");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService impostor = new JwtService("a-completely-different-secret-of-sufficient-length", ONE_HOUR_MS, ENVIRONMENT);
        String foreignToken = impostor.issueToken(donor());

        // A token minted elsewhere must not authenticate — this is what stops a
        // caller from forging a token that claims role ADMIN.
        assertThatThrownBy(() -> jwtService.parseClaims(foreignToken))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issueToken(donor());
        // Flip a character in the payload segment; the signature no longer matches.
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> jwtService.parseClaims(tampered))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void expirySecondsMirrorsConfiguredLifetime() {
        assertThat(jwtService.getExpirySeconds()).isEqualTo(3_600L);
    }
}
