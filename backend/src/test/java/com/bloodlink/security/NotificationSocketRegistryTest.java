package com.bloodlink.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

/**
 * Business Rule 32 for sockets already open: a password change closes the account's sockets signed in before it,
 * and nobody else's.
 */
class NotificationSocketRegistryTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant CHANGED_AT = Instant.parse("2026-10-31T12:00:00.700Z");

    private final NotificationSocketRegistry registry = new NotificationSocketRegistry();
    private final WebSocketHandler socketHandler = registry.decorate(mock(WebSocketHandler.class));

    /** Opens a socket through the decorated handler and signs it in with a token issued at this time. */
    private WebSocketSession openSocket(String id, UUID userId, String issuedAt) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        socketHandler.afterConnectionEstablished(session);
        registry.signedIn(id, userId, Date.from(Instant.parse(issuedAt)));
        return session;
    }

    @Test
    void aChangeClosesOnlyThatAccountsOlderSockets() throws Exception {
        WebSocketSession older = openSocket("older", USER_ID, "2026-10-31T11:59:59Z");
        WebSocketSession sameSecond = openSocket("same-second", USER_ID, "2026-10-31T12:00:00Z");
        WebSocketSession someoneElse = openSocket("someone-else", UUID.randomUUID(), "2026-10-31T11:00:00Z");

        registry.onPasswordChanged(new PasswordChangedEvent(USER_ID, CHANGED_AT));

        verify(older).close(NotificationSocketRegistry.PASSWORD_CHANGED);
        verify(sameSecond, never()).close(any(CloseStatus.class));
        verify(someoneElse, never()).close(any(CloseStatus.class));
    }

    @Test
    void aSocketThatAlreadyClosedOrWasRefusedIsLeftAlone() throws Exception {
        WebSocketSession closed = openSocket("closed", USER_ID, "2026-10-31T11:59:59Z");
        socketHandler.afterConnectionClosed(closed, CloseStatus.NORMAL);
        WebSocketSession refused = openSocket("refused", USER_ID, "2026-10-31T11:59:59Z");
        registry.signInRefused("refused");

        registry.onPasswordChanged(new PasswordChangedEvent(USER_ID, CHANGED_AT));

        verify(closed, never()).close(NotificationSocketRegistry.PASSWORD_CHANGED);
        verify(refused, never()).close(NotificationSocketRegistry.PASSWORD_CHANGED);
    }
}
