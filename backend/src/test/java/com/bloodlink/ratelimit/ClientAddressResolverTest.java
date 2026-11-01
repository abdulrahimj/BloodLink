package com.bloodlink.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Business Rule 34: which address a request is counted against, and that a visitor cannot choose it. */
class ClientAddressResolverTest {

    private static final String CONNECTION = "10.0.0.5";

    private static MockHttpServletRequest request(String... forwardedForHeaders) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(CONNECTION);
        for (String header : forwardedForHeaders) {
            request.addHeader("X-Forwarded-For", header);
        }
        return request;
    }

    private static String resolved(boolean trust, int hops, String... headers) {
        return new ClientAddressResolver(trust, hops).of(request(headers));
    }

    @Test
    void theHeaderIsIgnoredUnlessTrusted() {
        assertThat(resolved(false, 1, "8.8.8.8")).isEqualTo(CONNECTION);
    }

    @Test
    void trustedTheRightMostEntryIsUsedNotTheOneAVisitorCanFake() {
        assertThat(resolved(true, 1, "6.6.6.6, 41.223.0.7")).isEqualTo("41.223.0.7");
    }

    @Test
    void eachExtraTrustedHopCountsOneFurtherFromTheRight() {
        assertThat(resolved(true, 2, "6.6.6.6, 41.223.0.7, 172.70.1.1")).isEqualTo("41.223.0.7");
        assertThat(resolved(true, 3, "6.6.6.6, 41.223.0.7, 172.70.1.1, 10.1.1.1")).isEqualTo("41.223.0.7");
    }

    @Test
    void severalHeadersReadAsOneListInOrder() {
        assertThat(resolved(true, 1, "6.6.6.6", "41.223.0.7")).isEqualTo("41.223.0.7");
    }

    @Test
    void aMissingOrTooShortHeaderFallsBackToTheConnection() {
        assertThat(resolved(true, 1)).isEqualTo(CONNECTION);
        assertThat(resolved(true, 2, "41.223.0.7")).isEqualTo(CONNECTION);
        assertThat(resolved(true, 1, " , ")).isEqualTo(CONNECTION);
    }

    @Test
    void anEntryThatIsNotAnAddressFallsBackToTheConnection() {
        assertThat(resolved(true, 1, "bloodlink.example.com")).isEqualTo(CONNECTION);
        assertThat(resolved(true, 1, "41.223.0.7:443")).isEqualTo(CONNECTION);
    }

    @Test
    void anIpv6AddressIsCountedByItsNetworkSoRotatingWithinItGainsNothing() {
        String first = resolved(true, 1, "2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        String second = resolved(true, 1, "2001:db8:1:2:1111:2222:3333:4444");
        String otherNetwork = resolved(true, 1, "2001:db8:1:3:aaaa:bbbb:cccc:dddd");

        assertThat(first).isEqualTo(second).isNotEqualTo(otherNetwork);
    }

    @Test
    void oneAddressHasOneSpelling() {
        assertThat(resolved(true, 1, "::ffff:41.223.0.7")).isEqualTo("41.223.0.7");
    }

    @Test
    void lessThanOneHopIsRefusedAtStartup() {
        assertThatThrownBy(() -> new ClientAddressResolver(true, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
