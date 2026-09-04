package com.bloodlink.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers "authenticated, but not allowed here" with a JSON 403 in the same shape as
 * every other error — a donor calling an administrator endpoint, for instance.
 *
 * Needed because the URL rules in SecurityConfig are enforced inside the security
 * filter chain, which sits in front of the DispatcherServlet. GlobalExceptionHandler
 * never sees those denials, so without this the SPA would get Spring's default error
 * body, which carries no `message` field for the UI to show. (The advice's own
 * AccessDeniedException handler still covers method-level checks, which do reach it.)
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
            "timestamp", Instant.now().toString(),
            "status", HttpStatus.FORBIDDEN.value(),
            "error", "Forbidden",
            "message", "Your account does not have permission to perform this action."
        ));
    }
}
