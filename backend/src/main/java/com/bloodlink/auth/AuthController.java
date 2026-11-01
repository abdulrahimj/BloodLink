package com.bloodlink.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.ratelimit.AuthRateLimits;
import com.bloodlink.ratelimit.ClientAddressResolver;
import com.bloodlink.web.error.InvalidCredentialsException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Public authentication endpoints. Both are permitted without a token in
 * SecurityConfig — every other endpoint in the system requires one. Both are rate limited (Business Rule 34).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimits rateLimits;
    private final ClientAddressResolver clientAddress;

    AuthController(AuthService authService, AuthRateLimits rateLimits, ClientAddressResolver clientAddress) {
        this.authService = authService;
        this.rateLimits = rateLimits;
        this.clientAddress = clientAddress;
    }

    /**
     * Creates a donor account (User + linked Donor) and returns a token;
     * 201, with the same body shape as login. Only an account actually created counts towards the address's limit.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletRequest httpRequest
    ) {
        try (var attempt = rateLimits.beginRegistration(clientAddress.of(httpRequest))) {
            AuthResponse response = authService.register(request);
            attempt.succeeded();
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
    }

    /**
     * Exchanges phone + password for a signed access token, judging the limits before any password is read.
     * A malformed number stays sign-in's 400 (Business Rule 13) and counts as nothing: no password was tried.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String phone = PhoneNumber.normalize(request.phone());
        try (var attempt = rateLimits.beginSignIn(phone, clientAddress.of(httpRequest))) {
            try {
                AuthResponse response = authService.login(request);
                attempt.succeeded();
                return ResponseEntity.ok(response);
            } catch (InvalidCredentialsException wrongPhoneOrPassword) {
                attempt.failed();
                throw wrongPhoneOrPassword;
            }
        }
    }
}
