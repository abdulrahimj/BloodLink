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
 * Turns a Bearer token into an authenticated SecurityContext for one request, rebuilding the principal from claims.
 * Also checks the account is still ACTIVE on every request; a bad token or suspended account just leaves it empty.
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
                // Suspended or missing account: leave the context empty, exactly as for a bad signature.

            } catch (JwtException | IllegalArgumentException ex) {
                // Bad signature, expired, or a role name we no longer recognise.
                // Clear anything half-built and fall through unauthenticated.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Fail-closed: a missing row reads the same as SUSPENDED, never as active.
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
