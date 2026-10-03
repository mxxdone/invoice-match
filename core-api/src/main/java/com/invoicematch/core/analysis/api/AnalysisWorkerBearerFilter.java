package com.invoicematch.core.analysis.api;

import com.invoicematch.core.analysis.application.AnalysisWorkerAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stateless machine-worker Bearer authentication for the internal analysis
 * surface. It only extracts the Bearer value and maps a successful policy check
 * to a machine principal; the enable/secret policy and the constant-time
 * comparison live in {@link AnalysisWorkerAuthenticator}. It is installed only
 * on the dedicated internal filter chain, never globally.
 */
public class AnalysisWorkerBearerFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AnalysisWorkerAuthenticator authenticator;

    public AnalysisWorkerBearerFilter(AnalysisWorkerAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (authenticator.matches(token)) {
                var authentication = new PreAuthenticatedAuthenticationToken(
                        AnalysisWorkerAuthenticator.PRINCIPAL_NAME,
                        null,
                        List.of(new SimpleGrantedAuthority(AnalysisWorkerAuthenticator.AUTHORITY)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
