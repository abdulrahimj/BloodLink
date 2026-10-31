package com.bloodlink.security;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AccountAccess;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.web.error.ApiError;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a Bearer token into an authenticated SecurityContext for one request, rebuilding the principal from claims.
 * Checks the account every time: suspended or older than the last password change is a 401, must-change-password a 403.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractBearerToken(request);
        boolean passwordChangeRequired = false;

        // Skip if there is no token, or if something earlier in the chain already
        // authenticated this request — never overwrite an existing authentication.
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.parseClaims(token);
                UUID userId = jwtService.extractUserId(claims);
                // Fail-closed: a missing row reads the same as SUSPENDED, never as active.
                AccountAccess access = userRepository.findAccessById(userId).orElse(null);

                if (access != null && access.isActive() && access.tokenIssuedBeforePasswordChange(claims.getIssuedAt())) {
                    // Signed in before the password changed (Business Rule 32): unauthenticated, and the 401 says why.
                    request.setAttribute(PasswordChangedSignOut.REQUEST_ATTRIBUTE, Boolean.TRUE);
                } else if (access != null && access.isActive()) {
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
                    passwordChangeRequired = access.mustChangePassword() && !PasswordChangeGate.allows(request);
                }
                // Suspended or missing account: leave the context empty, exactly as for a bad signature.

            } catch (JwtException | IllegalArgumentException ex) {
                // Bad signature, expired, or a role name we no longer recognise.
                // Clear anything half-built and fall through unauthenticated.
                SecurityContextHolder.clearContext();
            }
        }

        if (passwordChangeRequired) {
            // Answered here, before any rule or controller runs, so nothing else is reachable (Business Rule 27).
            SecurityContextHolder.clearContext();
            refusePasswordChangeRequired(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** The 403 a flagged account gets everywhere but the gate's few addresses, with its distinct code. */
    private void refusePasswordChangeRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.withCode(
            HttpStatus.FORBIDDEN.value(), "Forbidden", PasswordChangeGate.ERROR_CODE, PasswordChangeGate.MESSAGE
        ));
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
