package com.bloodlink.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
// Spring Boot 4 ships Jackson 3, where ObjectMapper lives under tools.jackson
// (the annotations stay in com.fasterxml.jackson.annotation).
import tools.jackson.databind.ObjectMapper;

/**
 * Answers unauthenticated requests to protected endpoints with a JSON 401
 * instead of a login redirect the SPA could not use.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", "Unauthorized");
        // A token older than the account's last password change gets its own code and wording (Business Rule 32).
        if (request.getAttribute(PasswordChangedSignOut.REQUEST_ATTRIBUTE) != null) {
            body.put("code", PasswordChangedSignOut.ERROR_CODE);
            body.put("message", PasswordChangedSignOut.MESSAGE);
        } else {
            body.put("message", "Authentication is required to access this resource.");
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
