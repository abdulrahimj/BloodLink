package com.bloodlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;

import io.jsonwebtoken.Claims;

/**
 * Covers the same suspension gate as JwtAuthenticationFilterTest, at the point a
 * WebSocket session is opened rather than an HTTP request is served — a new
 * CONNECT with a suspended account's token must be refused for the same reason a
 * new REST call with it is.
 */
@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TOKEN = "a-valid-looking-token";

    @Mock private JwtService jwtService;
    @Mock private UserRepository userRepository;

    private StompAuthChannelInterceptor interceptor;

    private void stubToken() {
        interceptor = new StompAuthChannelInterceptor(jwtService, userRepository);

        Claims claims = mock(Claims.class);
        when(jwtService.parseClaims(TOKEN)).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(USER_ID);
        when(jwtService.extractRole(claims)).thenReturn(Role.DONOR.name());
    }

    private Message<byte[]> connectFrameWithBearerToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + TOKEN);
        // Without this, building the message freezes the accessor, and preSend's
        // accessor.setUser(...) — the whole point of a successful CONNECT — throws
        // "Already immutable" instead of exercising anything. Real inbound STOMP
        // frames are built the same way for exactly this reason: the accessor has
        // to stay writable until a handler further down the chain has named a user.
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void anActiveAccountsTokenIsAcceptedAndNamesThePrincipal() {
        stubToken();
        when(userRepository.findStatusById(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        Message<byte[]> connect = connectFrameWithBearerToken();
        interceptor.preSend(connect, null);

        StompHeaderAccessor result = StompHeaderAccessor.wrap(connect);
        assertThat(((StompPrincipal) result.getUser()).userId()).isEqualTo(USER_ID);
    }

    @Test
    void aSuspendedAccountCannotOpenANewSession() {
        stubToken();
        when(userRepository.findStatusById(USER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> interceptor.preSend(connectFrameWithBearerToken(), null))
            .isInstanceOf(StompAuthenticationException.class)
            .hasMessageContaining("suspended");
    }

    @Test
    void anAccountThatNoLongerExistsIsTreatedTheSameAsSuspended() {
        stubToken();
        when(userRepository.findStatusById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(connectFrameWithBearerToken(), null))
            .isInstanceOf(StompAuthenticationException.class);
    }
}
