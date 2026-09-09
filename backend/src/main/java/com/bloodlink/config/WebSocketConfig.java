package com.bloodlink.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.bloodlink.security.StompAuthChannelInterceptor;

/**
 * The WebSocket half of the Application Layer in Figure 3.1.
 *
 * Its whole job is server-to-client push: low-stock alerts and new-request alerts
 * to blood bank staff, donation appeals to donors, and request status changes to
 * hospital staff. Everything a client *does* — submitting a request, approving one,
 * adding stock — still travels over REST. That split is deliberate and is why no
 * @MessageMapping handler exists anywhere in this project: there is no client-to-
 * server message this server accepts, so there is no inbound surface to secure
 * beyond the CONNECT frame.
 *
 * The broker is Spring's in-memory simple broker rather than a real message broker
 * (RabbitMQ, ActiveMQ). For two blood banks and two hospitals in one city running a
 * single backend instance, an external broker would add an operational dependency
 * with nothing to show for it; the trade-off is that messages are not persisted or
 * shared across instances, which is acceptable because the durable copy of every
 * notification is the Notification row in PostgreSQL. A client that was offline
 * misses the push and picks the notification up from GET /api/notifications
 * instead — the socket is an accelerator, never the record.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Where a client subscribes for its own notifications, after /user resolution. */
    public static final String USER_NOTIFICATION_QUEUE = "/queue/notifications";

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final List<String> allowedOrigins;

    WebSocketConfig(
        StompAuthChannelInterceptor stompAuthChannelInterceptor,
        @Value("${bloodlink.cors.allowed-origins}") List<String> allowedOrigins
    ) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Only /queue is enabled. /topic would be a broadcast every subscriber
        // receives, and nothing this system pushes is public: stock levels,
        // patient references and request outcomes are all addressed to named staff
        // or a named donor, so leaving a broadcast destination configured would be
        // an open door with no door frame behind it.
        registry.enableSimpleBroker("/queue");
        // Prefix for the per-user destinations SimpMessagingTemplate writes to.
        // Subscribing to "/user/queue/notifications" is rewritten by Spring into a
        // destination private to that session, so one signed-in user physically
        // cannot subscribe to another's queue by guessing a destination name.
        registry.setUserDestinationPrefix("/user");
        // Declared for completeness even though no @MessageMapping exists: without
        // it, an inbound frame to an unrecognised destination is handled less
        // clearly than being routed nowhere.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.toArray(String[]::new);

        // Registered twice on purpose. The SockJS variant is what CLAUDE.md
        // specifies and what the frontend uses — it falls back to HTTP streaming or
        // polling on a network that blocks WebSocket upgrades, which is the point of
        // it for facilities on constrained connections. The plain variant is the
        // same endpoint without that fallback, kept so a non-browser client (a test,
        // or a diagnostic script) can connect with a raw WebSocket and not have to
        // speak the SockJS framing protocol.
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
        registry.addEndpoint("/ws").setAllowedOrigins(origins).withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Reads the JWT off the CONNECT frame and names the session's principal.
        // Every push destination is resolved from that principal, so without this
        // interceptor there would be nobody to deliver to.
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
