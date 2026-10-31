package com.bloodlink.security;

import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AccountAccess;
import com.bloodlink.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * Identifies a WebSocket client from the JWT on its STOMP CONNECT frame (browsers cannot set a handshake header).
 * Missing, invalid, suspended, older than the last password change, or must-change-password: the CONNECT fails.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final NotificationSocketRegistry socketRegistry;

    StompAuthChannelInterceptor(
        JwtService jwtService, UserRepository userRepository, NotificationSocketRegistry socketRegistry
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.socketRegistry = socketRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = extractBearerToken(accessor.getFirstNativeHeader(AUTH_HEADER));
        if (token == null) {
            throw new StompAuthenticationException("A bearer token is required to open a BloodLink socket.");
        }

        String sessionId = accessor.getSessionId();
        try {
            Claims claims = jwtService.parseClaims(token);
            UUID userId = jwtService.extractUserId(claims);
            Role role = Role.valueOf(jwtService.extractRole(claims));
            socketRegistry.signedIn(sessionId, userId, claims.getIssuedAt());

            AccountAccess access = userRepository.findAccessById(userId).orElse(null);
            if (access == null || !access.isActive()) {
                throw new StompAuthenticationException("This account has been suspended. Sign in again.");
            }
            // A token from before the last password change opens nothing (Business Rule 32).
            if (access.tokenIssuedBeforePasswordChange(claims.getIssuedAt())) {
                throw new StompAuthenticationException(PasswordChangedSignOut.MESSAGE);
            }
            // No notifications until the temporary password is replaced (Business Rule 27).
            if (access.mustChangePassword()) {
                throw new StompAuthenticationException(PasswordChangeGate.MESSAGE);
            }

            // What every later /user/queue/... delivery is routed by.
            accessor.setUser(new StompPrincipal(userId, role));
        } catch (StompAuthenticationException ex) {
            socketRegistry.signInRefused(sessionId);
            throw ex;
        } catch (JwtException | IllegalArgumentException ex) {
            // Bad signature, expired, or a role name this build no longer knows.
            socketRegistry.signInRefused(sessionId);
            throw new StompAuthenticationException("This session's token was rejected. Sign in again.");
        }

        return message;
    }

    /** Pulls the raw token out of the CONNECT frame's header, or null if absent/not Bearer. */
    private String extractBearerToken(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
