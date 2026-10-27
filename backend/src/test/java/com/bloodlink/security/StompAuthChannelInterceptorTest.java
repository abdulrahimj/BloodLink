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
import com.bloodlink.repository.AccountAccess;
import com.bloodlink.repository.UserRepository;

import io.jsonwebtoken.Claims;

/**
 * The same suspension gate as JwtAuthenticationFilterTest, at WebSocket CONNECT.
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
        // Keeps the accessor mutable so preSend's setUser works, as real inbound STOMP frames do.
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void anActiveAccountsTokenIsAcceptedAndNamesThePrincipal() {
        stubToken();
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.of(new AccountAccess(AccountStatus.ACTIVE, false)));

        Message<byte[]> connect = connectFrameWithBearerToken();
        interceptor.preSend(connect, null);

        StompHeaderAccessor result = StompHeaderAccessor.wrap(connect);
        assertThat(((StompPrincipal) result.getUser()).userId()).isEqualTo(USER_ID);
    }

    @Test
    void aSuspendedAccountCannotOpenANewSession() {
        stubToken();
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.of(new AccountAccess(AccountStatus.SUSPENDED, false)));

        assertThatThrownBy(() -> interceptor.preSend(connectFrameWithBearerToken(), null))
            .isInstanceOf(StompAuthenticationException.class)
            .hasMessageContaining("suspended");
    }

    @Test
    void anAccountThatNoLongerExistsIsTreatedTheSameAsSuspended() {
        stubToken();
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(connectFrameWithBearerToken(), null))
            .isInstanceOf(StompAuthenticationException.class);
    }

    @Test
    void anAccountThatMustChangeItsPasswordCannotOpenASession() {
        stubToken();
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.of(new AccountAccess(AccountStatus.ACTIVE, true)));

        // Business Rule 27: no notifications until the temporary password is replaced.
        assertThatThrownBy(() -> interceptor.preSend(connectFrameWithBearerToken(), null))
            .isInstanceOf(StompAuthenticationException.class)
            .hasMessageContaining("set your own password");
    }
}
