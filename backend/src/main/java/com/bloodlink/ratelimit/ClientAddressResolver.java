package com.bloodlink.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Which internet address a request came from, for Business Rule 34: the real connection, or with proxy headers trusted
 * the X-Forwarded-For entry the nearest trusted proxy added, counted from the right because a visitor can fake the left.
 */
@Component
public class ClientAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientAddressResolver.class);
    private static final int IPV6_NETWORK_BYTES = 8;

    private final boolean trustProxyHeaders;
    private final int trustedHops;

    ClientAddressResolver(
        @Value("${bloodlink.rate-limit.trust-proxy-headers:${BLOODLINK_TRUST_PROXY_HEADERS:false}}") boolean trustProxyHeaders,
        @Value("${bloodlink.rate-limit.trusted-proxy-hops:${BLOODLINK_TRUSTED_PROXY_HOPS:1}}") int trustedHops
    ) {
        if (trustedHops < 1) {
            throw new IllegalArgumentException("BLOODLINK_TRUSTED_PROXY_HOPS must be at least 1.");
        }
        this.trustProxyHeaders = trustProxyHeaders;
        this.trustedHops = trustedHops;
    }

    /** A header missing, too short or malformed falls back to the connection, never to a value a visitor chose. */
    public String of(HttpServletRequest request) {
        String connection = request.getRemoteAddr();
        if (!trustProxyHeaders) {
            return keyFor(connection, connection);
        }
        List<String> entries = forwardedEntries(request);
        String chosen = entries.size() >= trustedHops ? entries.get(entries.size() - trustedHops) : connection;
        // For checking the hop count after deploying (DEPLOYMENT.md); off unless DEBUG is switched on.
        log.debug("X-Forwarded-For {}; connection {}; using {}", entries, connection, chosen);
        return keyFor(chosen, connection);
    }

    /** Every entry, left to right, across however many X-Forwarded-For headers arrived. */
    private List<String> forwardedEntries(HttpServletRequest request) {
        List<String> entries = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            Arrays.stream(header.split(",")).map(String::trim).filter(entry -> !entry.isEmpty()).forEach(entries::add);
        }
        return entries;
    }

    private String keyFor(String address, String connection) {
        try {
            return canonical(InetAddress.ofLiteral(address));
        } catch (IllegalArgumentException notAnAddress) {
            return connection;
        }
    }

    /** One spelling per address, and one key per IPv6 network: a single home is given 2^64 addresses to rotate through. */
    private String canonical(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address.getHostAddress();
        }
        try {
            byte[] network = Arrays.copyOf(address.getAddress(), 16);
            Arrays.fill(network, IPV6_NETWORK_BYTES, network.length, (byte) 0);
            return InetAddress.getByAddress(network).getHostAddress();
        } catch (UnknownHostException impossible) {
            return address.getHostAddress();
        }
    }
}
