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
 * The one place a suspension actually bites for a request made with a token
 * issued before it happened. AuthService refuses a suspended account only at the
 * moment of login; every request in between is this filter's decision, so a gap
 * here would mean "suspended" only ever meant "cannot get a new token" — exactly
 * what the business requirement says a suspension must not be.
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
        // Lenient: the filter only reaches extractRole once accountIsActive has
        // already returned true, so the suspended/missing-account tests below
        // never call it — that is the behaviour under test in those cases, not
        // an oversight this stub needs to paper over.
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

        // Left unauthenticated, not rejected outright — the request still reaches
        // the servlet chain (a public endpoint would still be served); it is
        // SecurityConfig's role rules that turn "no authentication" into a 401
        // for anything protected. This filter's only job is to refuse to vouch
        // for a suspended account.
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
