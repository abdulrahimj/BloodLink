package com.bloodlink.config;

import java.util.List;

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

    SecurityConfig(
        JwtAuthenticationFilter jwtAuthenticationFilter,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
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

                // --- Administrator: account management and aggregate reporting ---
                .requestMatchers("/api/users/**", "/api/reports/**").hasRole(Role.ADMIN.name())

                // --- Blood bank / lab staff: own bank's stock and donation appeals ---
                // Reading inventory is open to all signed-in staff (hospitals need a live
                // view across banks); only bank staff may change it.
                .requestMatchers(HttpMethod.GET, "/api/inventory/**").authenticated()
                .requestMatchers("/api/inventory/**", "/api/appeals/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())
                .requestMatchers("/api/donations/**")
                    .hasAnyRole(Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- Blood requests: hospitals raise them, bank staff act on them ---
                .requestMatchers("/api/requests/**").hasAnyRole(
                    Role.HOSPITAL_STAFF.name(), Role.BLOOD_BANK_STAFF.name(), Role.ADMIN.name())

                // --- Donor's own profile, history and eligibility ---
                .requestMatchers("/api/donors/**")
                    .hasAnyRole(Role.DONOR.name(), Role.ADMIN.name())

                // Every role receives notifications, so this only needs a valid token;
                // scoping to the recipient happens in the service layer.
                .requestMatchers("/api/notifications/**").authenticated()

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
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:5174", "http://localhost:5175"));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
