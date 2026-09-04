package com.bloodlink.security;

import java.io.IOException;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bloodlink.domain.enums.Role;

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
 * The principal is rebuilt entirely from the token's signed claims — no database
 * lookup — because the signature already guarantees the claims are ours and unaltered.
 *
 * A missing, malformed, or expired token is never an error here: the filter simply
 * leaves the context empty and continues. Whether that is acceptable is the
 * authorisation layer's decision, which then returns 401 for a protected endpoint
 * and serves a public one normally.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
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

                AuthenticatedUser principal = new AuthenticatedUser(
                    jwtService.extractUserId(claims),
                    claims.getSubject(),
                    null, // no password on a token-authenticated request — it was never sent
                    Role.valueOf(jwtService.extractRole(claims))
                );

                var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities()
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException | IllegalArgumentException ex) {
                // Bad signature, expired, or a role name we no longer recognise.
                // Clear anything half-built and fall through unauthenticated.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
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
