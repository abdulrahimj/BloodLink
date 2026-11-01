package com.bloodlink.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.bloodlink.passwordreset.PasswordResetRequestService.Outcome;
import com.bloodlink.passwordreset.dto.PasswordResetRequestPayload;
import com.bloodlink.ratelimit.AuthRateLimits;
import com.bloodlink.ratelimit.ClientAddressResolver;
import com.bloodlink.web.error.InvalidPhoneNumberException;

/**
 * Business Rule 33's public form at the controller: one answer whatever happened, never sooner than the minimum time,
 * and the support phone as configured. No Spring context needed.
 */
class PasswordResetRequestControllerTest {

    private static final long MIN_MILLIS = 120;

    private final PasswordResetRequestService service = mock(PasswordResetRequestService.class);
    private final AuthRateLimits rateLimits = mock(AuthRateLimits.class);
    private final ClientAddressResolver clientAddress = mock(ClientAddressResolver.class);
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    private PasswordResetRequestController controller(String supportPhone) {
        return new PasswordResetRequestController(service, rateLimits, clientAddress, MIN_MILLIS, supportPhone);
    }

    @Test
    void everyOutcomeGetsTheSameAnswerAndNoneComesSoonerThanTheMinimum() {
        for (Outcome outcome : Outcome.values()) {
            when(service.request(anyString())).thenReturn(outcome);
            long started = System.nanoTime();

            var response = controller("").request(new PasswordResetRequestPayload("076 900 005"), httpRequest);

            assertThat((System.nanoTime() - started) / 1_000_000).as(outcome.name()).isGreaterThanOrEqualTo(MIN_MILLIS);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().message()).isEqualTo("If this number is registered, an administrator will contact you.");
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        }
        // Normalised exactly as sign-in does before anything is looked up.
        verify(service, org.mockito.Mockito.times(Outcome.values().length)).request("+23276900005");
    }

    @Test
    void anUnexpectedFailureStillGetsTheSameAnswer() {
        when(service.request(anyString())).thenThrow(new IllegalStateException("database unavailable"));

        var response = controller(null).request(new PasswordResetRequestPayload("+232 76 900 005"), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().message()).isEqualTo("If this number is registered, an administrator will contact you.");
    }

    @Test
    void aMalformedNumberIsSignInsOwn400AndLooksNothingUp() {
        assertThatThrownBy(() -> controller("").request(new PasswordResetRequestPayload("07690000"), httpRequest))
            .isInstanceOf(InvalidPhoneNumberException.class);
        verifyNoInteractions(service);
    }

    @Test
    void theSupportPhoneIsShownAsConfiguredOrNotAtAll() {
        assertThat(controller(" +232 76 000 111 ").supportContact().phone()).isEqualTo("+232 76 000 111");
        assertThat(controller("").supportContact().phone()).isNull();
        assertThat(controller("   ").supportContact().phone()).isNull();
        assertThat(controller(null).supportContact().phone()).isNull();
    }
}
