package com.invoicematch.core.analysis.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.application.AnalysisWorkerAuthenticator;
import com.invoicematch.core.analysis.application.AnalysisWorkerProperties;
import com.invoicematch.core.invoicecase.api.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Dedicated security chain for the machine analysis surface. It is isolated from
 * the human {@code /api/**} chain: no HTTP Basic, no session, and only the exact
 * machine method/paths are reachable, each requiring the worker authority
 * granted by {@link AnalysisWorkerBearerFilter}. Every other internal path is
 * denied and never disclosed. When the surface is disabled (the default) the
 * filter never authenticates, so every internal request is 401.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AnalysisWorkerProperties.class)
public class AnalysisWorkerSecurityConfiguration {

    private static final String INTERNAL_PATTERN = "/internal/analysis-runs/**";

    @Bean
    @Order(1)
    SecurityFilterChain analysisWorkerSecurityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            AnalysisWorkerAuthenticator authenticator) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> writeError(
                objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required");
        AccessDeniedHandler deniedHandler = (request, response, exception) -> writeError(
                objectMapper, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access is denied");

        http.securityMatcher(INTERNAL_PATTERN,"/internal/proposal-runs/**","/internal/graph-runs/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/internal/analysis-runs/*/claim")
                        .hasAuthority(AnalysisWorkerAuthenticator.AUTHORITY)
                        .requestMatchers(HttpMethod.POST, "/internal/analysis-runs/*/failures", "/internal/analysis-runs/*/defer")
                        .hasAuthority(AnalysisWorkerAuthenticator.AUTHORITY)
                        .requestMatchers(HttpMethod.POST, "/internal/analysis-runs/*/heartbeat")
                        .hasAuthority(AnalysisWorkerAuthenticator.AUTHORITY)
                        .requestMatchers(HttpMethod.POST, "/internal/analysis-runs/*/results")
                        .hasAuthority(AnalysisWorkerAuthenticator.AUTHORITY)
                        .requestMatchers(HttpMethod.POST, "/internal/analysis-runs/*/documents/*/source")
                        .hasAuthority(AnalysisWorkerAuthenticator.AUTHORITY)
                        .requestMatchers(HttpMethod.POST,"/internal/proposal-runs/*/claim","/internal/proposal-runs/*/defer",
                            "/internal/proposal-runs/*/heartbeat","/internal/proposal-runs/*/calls","/internal/proposal-runs/*/checkpoints",
                            "/internal/proposal-runs/*/complete","/internal/proposal-runs/*/failures","/internal/proposal-runs/*/tools",
                            "/internal/proposal-runs/*/policies","/internal/proposal-runs/*/documents/*/source")
                        .hasAuthority(AnalysisWorkerAuthenticator.AUTHORITY)
                        .requestMatchers(HttpMethod.POST,"/internal/graph-runs/*/claim","/internal/graph-runs/*/heartbeat",
                            "/internal/graph-runs/*/checkpoints","/internal/graph-runs/*/checkpoints/read",
                            "/internal/graph-runs/*/writes","/internal/graph-runs/*/waiting")
                        .hasAuthority(AnalysisWorkerAuthenticator.AUTHORITY)
                        .anyRequest()
                        .denyAll())
                .addFilterBefore(
                        new AnalysisWorkerBearerFilter(authenticator), AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    private static void writeError(
            ObjectMapper objectMapper, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), new ApiError(code, message));
    }
}
