package com.bloodlink.security;

import java.io.IOException;
import java.util.UUID;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns a "Authorization: Bearer <token>" header into an authenticated
 * SecurityContext for the duration of one request.
 *
 * Runs before Spring Security's authorisation checks, so by the time a rule such as
 * hasRole('BLOOD_BANK_STAFF') is evaluated the caller's role is already established.
 *
 * The principal's identity and role are rebuilt entirely from the token's signed
 * claims — no lookup needed for those, because the signature already guarantees the
 * claims are ours and unaltered. Whether the account is still allowed to act is a
 * different question the token cannot answer on its own: a stateless JWT stays
 * cryptographically valid for its whole lifetime (up to 24 hours) regardless of
 * anything that happens to the account afterwards. An administrator suspending
 * someone must take effect now, not whenever that person's current token happens to
 * expire, so this filter asks the database for the one fact a signature cannot
 * carry — {@link #accountIsActive} — on every authenticated request. That is a
 * deliberate departure from "no database lookup": one indexed read by primary key
 * is the cost of "suspended means suspended immediately", and there is no way to
 * honour that requirement from the token's contents alone.
 *
 * A missing, malformed, or expired token — or one for an account that is no longer
 * active — is never an error here: the filter simply leaves the context empty and
 * continues. Whether that is acceptable is the authorisation layer's decision, which
 * then returns 401 for a protected endpoint and serves a public one normally.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractBearerToken(request);

        // Skip if there is no token, or if something earlier in the chain already
        // authenticated this request — never overwrite an existing authentication.
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.parseClaims(token);
                UUID userId = jwtService.extractUserId(claims);

                if (accountIsActive(userId)) {
                    AuthenticatedUser principal = new AuthenticatedUser(
                        userId,
                        claims.getSubject(),
                        null, // no password on a token-authenticated request — it was never sent
                        Role.valueOf(jwtService.extractRole(claims))
                    );

                    var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
                // Suspended, or the account no longer exists: leave the context empty.
                // Falling through unauthenticated is exactly how a bad signature is
                // already handled below, and a suspended account's existing token
                // deserves no better treatment than one that was never valid.

            } catch (JwtException | IllegalArgumentException ex) {
                // Bad signature, expired, or a role name we no longer recognise.
                // Clear anything half-built and fall through unauthenticated.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Fail-closed by construction: a missing row (should not happen for a validly
     * signed token, but the database is the source of truth, not the assumption)
     * reads the same as SUSPENDED — never as active.
     */
    private boolean accountIsActive(UUID userId) {
        return userRepository.findStatusById(userId)
            .map(status -> status == AccountStatus.ACTIVE)
            .orElse(false);
    }

    /** Pulls the raw token out of the Authorization header, or null if absent/not Bearer. */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
