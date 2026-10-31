package com.bloodlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
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
import com.bloodlink.repository.AccountAccess;
import com.bloodlink.repository.UserRepository;

import io.jsonwebtoken.Claims;
import tools.jackson.databind.ObjectMapper;

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
        filter = new JwtAuthenticationFilter(jwtService, userRepository, new ObjectMapper());

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
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.of(new AccountAccess(AccountStatus.ACTIVE, false)));

        runFilterWithBearerToken();

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(((AuthenticatedUser) authentication.getPrincipal()).getId()).isEqualTo(USER_ID);
    }

    @Test
    void aSuspendedAccountsExistingTokenDoesNotAuthenticate() throws Exception {
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.of(new AccountAccess(AccountStatus.SUSPENDED, false)));

        runFilterWithBearerToken();

        // Left unauthenticated, not rejected: SecurityConfig turns that into a 401 for protected endpoints.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aTokenForAnAccountThatNoLongerExistsIsTreatedTheSameAsSuspended() throws Exception {
        // Fails closed: an absent row is never read as active, only as inactive.
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.empty());

        runFilterWithBearerToken();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aRequestWithNoTokenIsUntouched() throws Exception {
        filter = new JwtAuthenticationFilter(jwtService, userRepository, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ---------- Business Rule 27: an account that must replace its temporary password ----------

    /** Runs the filter for a flagged, active account on one method and path; returns the response. */
    private MockHttpServletResponse runFlaggedRequest(String method, String path, MockFilterChain chain) throws Exception {
        setUp();
        when(userRepository.findAccessById(USER_ID)).thenReturn(Optional.of(new AccountAccess(AccountStatus.ACTIVE, true)));
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void aFlaggedAccountIsRefusedEverywhereElseWithADistinctCode() throws Exception {
        for (String[] call : new String[][] {
            { "GET", "/api/notifications" }, { "GET", "/api/admin/users" }, { "POST", "/api/requests" },
            // Its own account is readable, but not editable, until the password is changed.
            { "PATCH", "/api/users/me" },
        }) {
            SecurityContextHolder.clearContext();
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = runFlaggedRequest(call[0], call[1], chain);

            assertThat(response.getStatus()).as(call[0] + " " + call[1]).isEqualTo(403);
            assertThat(response.getContentAsString())
                .contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\"")
                .contains("set your own password");
            // Answered by the filter itself: no rule, controller or later filter ever saw the request.
            assertThat(chain.getRequest()).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void aFlaggedAccountMayReadItsOwnAccountAndChangeItsPassword() throws Exception {
        for (String[] call : new String[][] { { "GET", "/api/users/me" }, { "PATCH", "/api/users/me/password" } }) {
            SecurityContextHolder.clearContext();
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = runFlaggedRequest(call[0], call[1], chain);

            assertThat(response.getStatus()).as(call[0] + " " + call[1]).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            assertThat(((AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getId())
                .isEqualTo(USER_ID);
        }
    }

    @Test
    void aStrayTokenOnAPublicEndpointDoesNotBlockSigningInAgain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = runFlaggedRequest("POST", "/api/auth/login", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    // ---------- Business Rule 32: a token from before the last password change ----------

    private static final Instant CHANGED_AT = Instant.parse("2026-10-31T12:00:00.700Z");

    /** Runs the filter for an active account whose password changed at CHANGED_AT (or never), with a token of that age. */
    private MockHttpServletRequest runWithTokenIssuedAt(String issuedAt, Instant passwordChangedAt) throws Exception {
        filter = new JwtAuthenticationFilter(jwtService, userRepository, new ObjectMapper());
        Claims claims = mock(Claims.class);
        when(jwtService.parseClaims(TOKEN)).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(USER_ID);
        lenient().when(jwtService.extractRole(claims)).thenReturn(Role.DONOR.name());
        when(claims.getIssuedAt()).thenReturn(Date.from(Instant.parse(issuedAt)));
        when(userRepository.findAccessById(USER_ID))
            .thenReturn(Optional.of(new AccountAccess(AccountStatus.ACTIVE, false, passwordChangedAt)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return request;
    }

    @Test
    void aTokenIssuedBeforeThePasswordChangedIsNoLongerASignIn() throws Exception {
        MockHttpServletRequest request = runWithTokenIssuedAt("2026-10-31T11:59:59Z", CHANGED_AT);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // Marked, so the 401 the entry point sends says why.
        assertThat(request.getAttribute(PasswordChangedSignOut.REQUEST_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void aTokenIssuedInTheSameSecondAsTheChangeStillWorks() throws Exception {
        // Issued-at has whole seconds only: 12:00:00 is the change's own second, so it is not older.
        MockHttpServletRequest request = runWithTokenIssuedAt("2026-10-31T12:00:00Z", CHANGED_AT);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(request.getAttribute(PasswordChangedSignOut.REQUEST_ATTRIBUTE)).isNull();
    }

    @Test
    void anAccountWhosePasswordNeverChangedSinceV29HasNoRestriction() throws Exception {
        MockHttpServletRequest request = runWithTokenIssuedAt("2020-01-01T00:00:00Z", null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(request.getAttribute(PasswordChangedSignOut.REQUEST_ATTRIBUTE)).isNull();
    }

    @Test
    void theEntryPointNamesThePasswordChangeOnlyWhenTheFilterMarkedIt() throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(new ObjectMapper());

        MockHttpServletRequest marked = new MockHttpServletRequest();
        marked.setAttribute(PasswordChangedSignOut.REQUEST_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse signedOut = new MockHttpServletResponse();
        entryPoint.commence(marked, signedOut, null);
        assertThat(signedOut.getStatus()).isEqualTo(401);
        assertThat(signedOut.getContentAsString())
            .contains("\"code\":\"PASSWORD_CHANGED\"")
            .contains("Your password was changed. Please sign in again.");

        MockHttpServletResponse plain = new MockHttpServletResponse();
        entryPoint.commence(new MockHttpServletRequest(), plain, null);
        assertThat(plain.getStatus()).isEqualTo(401);
        assertThat(plain.getContentAsString()).doesNotContain("code").contains("Authentication is required");
    }
}
