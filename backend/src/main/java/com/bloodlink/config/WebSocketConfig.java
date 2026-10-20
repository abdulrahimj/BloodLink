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
 * The WebSocket half of the Application Layer: server-to-client push only, with no @MessageMapping handlers.
 * In-memory simple broker; the durable record is the Notification row, so a missed push is read from the API.
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
        // Only /queue is enabled: nothing pushed is public, so no broadcast /topic destination exists.
        registry.enableSimpleBroker("/queue");
        // Per-user destinations: "/user/queue/notifications" is rewritten to a session-private destination,
        // so nobody can subscribe to another user's queue.
        registry.setUserDestinationPrefix("/user");
        // Declared for completeness even though no @MessageMapping exists, so stray inbound frames are routed nowhere.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.toArray(String[]::new);

        // Registered twice on purpose: SockJS (what the frontend uses, with HTTP fallbacks for constrained networks)
        // and plain WebSocket for non-browser clients such as tests.
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
        registry.addEndpoint("/ws").setAllowedOrigins(origins).withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Reads the JWT off the CONNECT frame and names the session's principal, which every push is addressed to.
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
