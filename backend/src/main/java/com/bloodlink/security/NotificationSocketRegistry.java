package com.bloodlink.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import com.bloodlink.repository.AccountAccess;

/**
 * Every open notification socket and the token it signed in with, so a password change closes the older ones
 * (Business Rule 32). In memory, like the simple broker: one server.
 */
@Component
public class NotificationSocketRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationSocketRegistry.class);

    /** The close a signed-out socket gets; the client's reconnect is then refused at CONNECT. */
    static final CloseStatus PASSWORD_CHANGED = CloseStatus.POLICY_VIOLATION.withReason(PasswordChangedSignOut.MESSAGE);

    private record SignIn(UUID userId, Date issuedAt) {
    }

    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, SignIn> signIns = new ConcurrentHashMap<>();

    /** Wraps the STOMP handler so every socket is known from opening to closing (WebSocketConfig registers it). */
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sockets.remove(session.getId());
                signIns.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** Recorded at CONNECT before the account is read, so a change committing in between still finds this socket. */
    void signedIn(String sessionId, UUID userId, Date issuedAt) {
        if (sessionId != null) {
            signIns.put(sessionId, new SignIn(userId, issuedAt));
        }
    }

    /** Forgets a CONNECT that was refused. */
    void signInRefused(String sessionId) {
        if (sessionId != null) {
            signIns.remove(sessionId);
        }
    }

    /** After the change commits: closes this account's sockets whose token predates it; newer ones stay open. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onPasswordChanged(PasswordChangedEvent event) {
        closeSignedInBefore(event.userId(), event.changedAt());
    }

    void closeSignedInBefore(UUID userId, Instant changedAt) {
        signIns.forEach((sessionId, signIn) -> {
            if (signIn.userId().equals(userId) && AccountAccess.issuedBefore(signIn.issuedAt(), changedAt)) {
                signIns.remove(sessionId);
                WebSocketSession socket = sockets.get(sessionId);
                if (socket != null) {
                    close(socket);
                }
            }
        });
    }

    private static void close(WebSocketSession socket) {
        try {
            socket.close(PASSWORD_CHANGED);
        } catch (IOException | RuntimeException ex) {
            // Already closing on its own; nothing more to do.
            log.debug("Closing notification socket {} after a password change failed: {}", socket.getId(), ex.toString());
        }
    }
}
