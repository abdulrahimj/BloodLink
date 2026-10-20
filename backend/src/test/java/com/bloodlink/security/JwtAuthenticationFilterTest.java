package com.bloodlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;

import io.jsonwebtoken.Claims;

/**
 * Where a suspension bites for a token issued before it: every request after login is this filter's decision.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TOKEN = "a-valid-looking-token";

    @Mock private JwtService jwtService;
    @Mock private UserRepository userRepository;

    private JwtAuthenticationFilter filter;

    private void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, userRepository);

        Claims claims = mock(Claims.class);
        when(jwtService.parseClaims(TOKEN)).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(USER_ID);
        // Lenient: extractRole is only reached after accountIsActive is true, so the suspended tests never call it.
        lenient().when(jwtService.extractRole(claims)).thenReturn(Role.DONOR.name());
    }

    @AfterEach
    void clearContext() {
        // A leaked SecurityContext from one test could make a later, unrelated
        // test look authenticated when the filter never ran for it.
        SecurityContextHolder.clearContext();
    }

    private void runFilterWithBearerToken() throws Exception {
        setUp();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);
    }

    @Test
    void anActiveAccountsTokenAuthenticatesNormally() throws Exception {
        when(userRepository.findStatusById(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        runFilterWithBearerToken();

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(((AuthenticatedUser) authentication.getPrincipal()).getId()).isEqualTo(USER_ID);
    }

    @Test
    void aSuspendedAccountsExistingTokenDoesNotAuthenticate() throws Exception {
        when(userRepository.findStatusById(USER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        runFilterWithBearerToken();

        // Left unauthenticated, not rejected: SecurityConfig turns that into a 401 for protected endpoints.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aTokenForAnAccountThatNoLongerExistsIsTreatedTheSameAsSuspended() throws Exception {
        // Fails closed: an absent row is never read as active, only as inactive.
        when(userRepository.findStatusById(USER_ID)).thenReturn(Optional.empty());

        runFilterWithBearerToken();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aRequestWithNoTokenIsUntouched() throws Exception {
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
