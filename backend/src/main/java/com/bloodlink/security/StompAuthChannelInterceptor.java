package com.bloodlink.security;

import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * Establishes who a WebSocket client is, at the moment it opens its STOMP session.
 *
 * The browser cannot set an Authorization header on a WebSocket handshake — the
 * WebSocket API simply has no way to do it — so the JWT arrives one step later, as
 * a header on the STOMP CONNECT frame instead. That is the only frame this
 * inspects; every later frame on the session inherits the principal set here.
 *
 * A missing or invalid token fails the CONNECT outright rather than being allowed
 * through as an anonymous session. That is the opposite of how
 * {@link JwtAuthenticationFilter} treats a bad token over HTTP, and deliberately:
 * an unauthenticated HTTP request can still legitimately be served a public
 * endpoint, whereas an unidentified socket has no purpose at all here — every
 * message this server pushes is addressed to one named recipient.
 *
 * A suspended account is refused here too, for the same reason
 * {@link JwtAuthenticationFilter} checks it on every REST request: the token stays
 * cryptographically valid long after an administrator turns the account off. This
 * only stops a *new* CONNECT — a socket already open when the suspension happens
 * keeps receiving pushes until it disconnects, since closing an open session would
 * need a registry of live sessions this class does not keep. That gap is
 * considerably narrower than doing nothing: it closes the moment the tab is
 * reloaded, reconnects (this client auto-reconnects every five seconds), or is
 * closed, and every push it could still receive is an ordinary notification, never
 * a way to act on anything — no @MessageMapping handler accepts a message from a
 * client on this server.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    StompAuthChannelInterceptor(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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

        try {
            Claims claims = jwtService.parseClaims(token);
            UUID userId = jwtService.extractUserId(claims);
            Role role = Role.valueOf(jwtService.extractRole(claims));

            boolean active = userRepository.findStatusById(userId)
                .map(status -> status == AccountStatus.ACTIVE)
                .orElse(false);
            if (!active) {
                throw new StompAuthenticationException("This account has been suspended. Sign in again.");
            }

            // What every later /user/queue/... delivery is routed by.
            accessor.setUser(new StompPrincipal(userId, role));
        } catch (JwtException | IllegalArgumentException ex) {
            // Bad signature, expired, or a role name this build no longer knows.
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
