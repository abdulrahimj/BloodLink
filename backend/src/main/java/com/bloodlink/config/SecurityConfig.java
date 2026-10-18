package com.bloodlink.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.bloodlink.domain.enums.Role;
import com.bloodlink.security.JwtAuthenticationFilter;
import com.bloodlink.security.RestAccessDeniedHandler;
import com.bloodlink.security.RestAuthenticationEntryPoint;

/**
 * Stateless JWT security for the whole API.
 *
 * Two things are configured here: how a caller is identified (the JWT filter, plus the
 * BCrypt-backed provider used once at login), and what each role may reach.
 *
 * The authorisation rules below are the server-side half of the role gating —
 * CLAUDE.md requires that role-gated UI is never trusted on its own, so every rule
 * the frontend enforces visually is repeated here as the actual enforcement point.
 * Rules are written against the Role enum rather than string literals so a rename of
 * a role breaks the build instead of silently opening an endpoint.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    /** Shared with WebSocketConfig via bloodlink.cors.allowed-origins, so REST and
     *  the WebSocket handshake can never end up trusting different origins. */
    private final List<String> allowedOrigins;

    SecurityConfig(
        JwtAuthenticationFilter jwtAuthenticationFilter,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler,
        @Value("${bloodlink.cors.allowed-origins}") List<String> allowedOrigins
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // No cookies or server-side sessions are used, so there is no CSRF vector
            // to protect: the token travels in an Authorization header the browser
            // does not attach automatically.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Return JSON for both denial cases instead of Spring's defaults — a login
            // redirect for 401, and a body with no `message` for 403 — neither of which
            // an SPA calling fetch() can do anything useful with.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(auth -> auth
                // --- Public ---
                .requestMatchers("/api/health").permitAll()
                // Registration and login must be reachable without a token, by definition.
                .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                // The browser's pre-flight probe carries no Authorization header.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // When a request fails, the servlet container re-dispatches it to /error
                // to render the response. That internal dispatch carries no credentials,
                // so without this it is refused and every unhandled 500 reaches the
                // client disguised as a 401 — which hides the real fault.
                .requestMatchers("/error").permitAll()
                // The WebSocket handshake (and SockJS's /ws/info probe and fallback
                // transports) cannot carry an Authorization header — the browser's
                // WebSocket API provides no way to set one — so this filter chain has
                // nothing to authenticate at that point and would reject every socket.
                // Permitting the handshake is not permitting the socket: the token is
                // required on the STOMP CONNECT frame immediately afterwards, and
                // StompAuthChannelInterceptor closes the session if it is missing or
                // invalid. A connection that gets past this line and no further can
                // subscribe to nothing and receives nothing.
                .requestMatchers("/ws/**").permitAll()

                // --- Administrator: account management and aggregate reporting ---
                // Everything under one prefix, since every route here needs the same
                // rule: an administrator, and nothing scoped to their own facility
                // (an admin has none) the way the blood-bank and hospital rules below do.
                .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())

                // --- Blood banks: reference data (name/location) and inventory ---
                // The dashboard summary is an operational, staff-facing view (pending
                // requests, donor counts) — narrower than the plain bank listing, and
                // matched first since Spring evaluates these rules in order.
                .requestMatchers(HttpMethod.GET, "/api/blood-banks/*/dashboard")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())
                .requestMatchers(HttpMethod.GET, "/api/blood-banks/**").authenticated()
                // Hospitals are the same kind of reference data as blood banks (V6) —
                // readable by any signed-in role. Neither self-registers: the only
                // write path for either is under /api/admin/** above (Business Rule 19).
                .requestMatchers(HttpMethod.GET, "/api/hospitals/**").authenticated()
                // Batch-level detail (each batch's expiry, source and history) is for
                // blood bank staff and administrators only: hospitals see per-group
                // totals, never batches (Business Rule 24). Matched before the general
                // inventory read rule below, since Spring evaluates these in order.
                // Which bank a BLOOD_BANK_STAFF member may read is checked in
                // InventoryService, as it is for writes.
                .requestMatchers(HttpMethod.GET, "/api/inventory/*/batches", "/api/inventory/*/batches/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())
                // Reading inventory totals is open to all signed-in staff (hospitals need
                // a live view across banks). Changing stock — adding a batch, or writing
                // one off (POST .../batches/{id}/write-offs) — is for blood bank staff
                // ONLY: an administrator reads every bank's stock but changes none of it
                // (Stage 3b), and InventoryService.requireStockChangeAccess refuses ADMIN
                // again in case a route ever escapes this rule. Which bank a given
                // BLOOD_BANK_STAFF member may write to is enforced there too — a URL rule
                // can't see whose bank the path id belongs to.
                .requestMatchers(HttpMethod.GET, "/api/inventory/**").authenticated()
                .requestMatchers("/api/inventory/**").hasRole(Role.BLOOD_BANK_STAFF.name())
                .requestMatchers("/api/appeals/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())
                .requestMatchers("/api/donations/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- Blood requests: hospitals raise them, bank staff act on them ---
                .requestMatchers("/api/requests/**").hasAnyRole(
                    Role.HOSPITAL_STAFF.name(), Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- Donor lookup for "Record Donation" ---
                // The one route on /api/donors that is NOT the caller's own record,
                // and so the one with the opposite rule: bank staff must be able to
                // find the donor in front of them, and a donor must never be able to
                // look up another donor. Matched before the rule below, since Spring
                // applies the first rule that matches.
                .requestMatchers(HttpMethod.GET, "/api/donors/lookup")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- A blood bank's own donor roster (donors with a donation at
                // that bank) and each one's donation history there ---
                // Matched before the general /api/donors/** rule below for the same
                // reason /lookup is: this is bank staff looking at donors, not a
                // donor's own record.
                .requestMatchers(HttpMethod.GET, "/api/donors/roster/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- Donor's own profile, history and eligibility ---
                .requestMatchers("/api/donors/**")
                    .hasAnyRole(Role.DONOR.name(), Role.ADMIN.name())

                // Every role receives notifications, so this only needs a valid token;
                // scoping to the recipient happens in the service layer.
                .requestMatchers("/api/notifications/**").authenticated()

                // --- The caller's own account (Business Rule 16) ---
                // Every role has a name, email and password to manage, so any valid
                // token will do. Scoping is by construction: the controller reads the
                // account id from the token and the URL cannot name anyone else's.
                // Named explicitly rather than left to the default below, so that a
                // later tightening of that default cannot lock everyone out of their
                // own settings by accident.
                .requestMatchers("/api/users/me", "/api/users/me/**").authenticated()

                // Default deny: anything not named above still requires a token.
                .anyRequest().authenticated()
            )
            // Establish the caller from their token before authorisation is evaluated.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt for password storage (CLAUDE.md: passwords must be BCrypt-hashed).
     * Its per-password salt and deliberate slowness are what make a stolen
     * password_hash column impractical to reverse.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Wires phone-based user lookup to the BCrypt encoder. This is what actually
     * compares a submitted password against the stored hash during login.
     */
    @Bean
    DaoAuthenticationProvider daoAuthenticationProvider(
        UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /** Exposes the AuthenticationManager so AuthService can trigger a login check. */
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // The browser hides every response header but a handful from a page on another
        // origin unless it is listed here. Content-Disposition carries a Reports CSV's
        // file name (Business Rule 23); without this the download falls back to a
        // generic name. Exposing it lets the page read a file name, nothing more.
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
