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
 * Stateless JWT security for the whole API: how callers are identified and what each role may reach.
 * The server-side half of role gating; rules use the Role enum so a rename breaks the build.
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
            // No cookies or server sessions, so no CSRF vector: the token travels in an Authorization header.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Return JSON for 401 and 403 instead of Spring's defaults, which an SPA using fetch() cannot use.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(auth -> auth
                // --- Public ---
                .requestMatchers("/api/health").permitAll()
                // Registration and login must be reachable without a token, by definition.
                .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                // "Forgot password?" (Business Rule 33): asked for by someone who cannot sign in, by definition.
                .requestMatchers(HttpMethod.POST, "/api/auth/password-reset-requests").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/support-contact").permitAll()
                // The browser's pre-flight probe carries no Authorization header.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Error dispatches to /error carry no credentials; without this every unhandled 500 would
                // reach the client disguised as a 401.
                .requestMatchers("/error").permitAll()
                // The WebSocket handshake cannot carry an Authorization header, so it is permitted here; the token
                // is required on STOMP CONNECT, and StompAuthChannelInterceptor closes sessions without a valid one.
                .requestMatchers("/ws/**").permitAll()

                // --- Administrator: account management and aggregate reporting ---
                // One prefix, one rule: an administrator, with no facility scoping.
                .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())

                // --- Blood banks: reference data (name/location) and inventory ---
                // The staff-facing dashboard summary is matched first, since rules are evaluated in order.
                .requestMatchers(HttpMethod.GET, "/api/blood-banks/*/dashboard")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())
                .requestMatchers(HttpMethod.GET, "/api/blood-banks/**").authenticated()
                // Hospitals are reference data readable by any signed-in role; the only write path is
                // under /api/admin/** (Business Rule 19).
                .requestMatchers(HttpMethod.GET, "/api/hospitals/**").authenticated()
                // Batch-level detail is for bank staff and administrators only; hospitals see totals (Business Rule 24).
                // Matched before the general inventory rule; which bank staff may read is checked in InventoryService.
                .requestMatchers(HttpMethod.GET, "/api/inventory/*/batches", "/api/inventory/*/batches/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())
                // Reading inventory totals is open to all signed-in staff; changing stock is BLOOD_BANK_STAFF only.
                // InventoryService.requireStockChangeAccess repeats that (never ADMIN) and checks the bank is the caller's own.
                .requestMatchers(HttpMethod.GET, "/api/inventory/**").authenticated()
                .requestMatchers("/api/inventory/**").hasRole(Role.BLOOD_BANK_STAFF.name())
                .requestMatchers("/api/appeals/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())
                // Recording a donation creates stock, so it is BLOOD_BANK_STAFF only, not ADMIN (Business Rule 8);
                // refused again in DonationService. ADMIN reads donations through the reports.
                .requestMatchers("/api/donations/**").hasRole(Role.BLOOD_BANK_STAFF.name())

                // --- Blood requests: hospitals raise them, bank staff act on them ---
                // Only the raising hospital's staff cancel (Business Rule 35); matched before the general rule below.
                .requestMatchers(HttpMethod.POST, "/api/requests/*/cancel").hasRole(Role.HOSPITAL_STAFF.name())
                // ADMIN stays here for REJECT; RequestService.act refuses an ADMIN's CONTRIBUTE (a URL rule can't see it).
                .requestMatchers("/api/requests/**").hasAnyRole(
                    Role.HOSPITAL_STAFF.name(), Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- Donor lookup for "Record Donation" ---
                // Bank staff only, like Record Donation itself (Business Rule 8): not ADMIN, hospital staff or donors.
                // Matched before the general /api/donors/** rule below.
                .requestMatchers(HttpMethod.GET, "/api/donors/lookup").hasRole(Role.BLOOD_BANK_STAFF.name())

                // --- A blood bank's own donor roster and each donor's history there ---
                // Matched before the general /api/donors/** rule, like /lookup.
                .requestMatchers(HttpMethod.GET, "/api/donors/roster/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- Correcting a donor's blood group after a test (Business Rule 28) ---
                // Bank staff only, never ADMIN; refused again in DonorBloodGroupService. Before /api/donors/**.
                .requestMatchers(HttpMethod.PATCH, "/api/donors/*/blood-group").hasRole(Role.BLOOD_BANK_STAFF.name())

                // --- Donation visits (Business Rule 29): staff list their bank's, donors manage their own ---
                // Both refused again in DonationBookingService; the staff rule is matched first.
                .requestMatchers(HttpMethod.GET, "/api/bookings/upcoming").hasRole(Role.BLOOD_BANK_STAFF.name())
                .requestMatchers("/api/bookings", "/api/bookings/**").hasRole(Role.DONOR.name())

                // --- Donor's own profile, history and eligibility ---
                .requestMatchers("/api/donors/**")
                    .hasAnyRole(Role.DONOR.name(), Role.ADMIN.name())

                // Every role receives notifications, so this only needs a valid token;
                // scoping to the recipient happens in the service layer.
                // Accepting an appeal is a donor's answer (Business Rule 25); matched before the general rule below.
                .requestMatchers(HttpMethod.POST, "/api/notifications/*/accept")
                    .hasRole(Role.DONOR.name())
                .requestMatchers("/api/notifications/**").authenticated()

                // --- The caller's own account (Business Rule 16) --- any valid token; the id comes from the token.
                // Named explicitly so tightening the default below cannot lock everyone out of their own settings.
                .requestMatchers("/api/users/me", "/api/users/me/**").authenticated()

                // Default deny: anything not named above still requires a token.
                .anyRequest().authenticated()
            )
            // Establish the caller from their token before authorisation is evaluated.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt for password storage: its per-password salt and deliberate slowness
     * make a stolen password_hash impractical to reverse.
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
        // Readable by the other-origin page: a Reports CSV's file name (Business Rule 23) and a rate limit's wait (Rule 34).
        config.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
