package com.invoicematch.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.invoicecase.api.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless HTTP Basic security for the Core API.
 *
 * <p>Every {@code /api/**} route requires authentication; the actuator health
 * probes stay public. Unauthenticated protected access is 401 and an
 * authenticated but forbidden action is 403, both as the same JSON error body
 * the rest of the API uses. Business roles are attached to local demo users in
 * {@code application.yml}; there are no real secrets here.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(DemoSecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, ObjectMapper objectMapper, AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(
                objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required");
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(
                objectMapper, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                "Access is denied");
    }

    @Bean
    UserDetailsService demoUserDetailsService(DemoSecurityProperties properties) {
        List<UserDetails> users = properties.getUsers().stream()
                .map(SecurityConfiguration::toUserDetails)
                .toList();
        if (users.isEmpty()) {
            // Fail closed: with no configured identities every /api/** request is
            // 401. Demo identities live only in the local/demo or test profile.
            org.slf4j.LoggerFactory.getLogger(SecurityConfiguration.class)
                    .warn("No demo identities are configured; protected API access is denied."
                            + " Activate the 'local' profile for local development.");
        }
        return new InMemoryUserDetailsManager(users);
    }

    private static UserDetails toUserDetails(DemoSecurityProperties.DemoUser demo) {
        String username = demo.getUsername();
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("A demo user must have a username");
        }
        if (SecurityPrincipals.RESERVED.equals(username)) {
            throw new IllegalStateException(
                    "The reserved identity '" + SecurityPrincipals.RESERVED + "' cannot be a configured login");
        }
        if (demo.getRoles() == null || demo.getRoles().isEmpty()) {
            throw new IllegalStateException("Demo user " + username + " must have at least one role");
        }
        String[] roles = demo.getRoles().stream()
                .peek(role -> {
                    if (Role.fromAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role).isEmpty()) {
                        throw new IllegalStateException(
                                "Demo user " + username + " has an unknown role: " + role);
                    }
                })
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .distinct()
                .toArray(String[]::new);
        return User.withUsername(username)
                .password(demo.getPassword())
                .roles(roles)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static void writeError(
            ObjectMapper objectMapper, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiError(code, message));
    }
}
