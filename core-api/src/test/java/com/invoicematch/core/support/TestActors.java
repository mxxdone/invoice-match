package com.invoicematch.core.support;

import java.util.Arrays;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Establishes a server-side principal for direct service calls in tests that
 * bypass the HTTP security filter chain. HTTP tests use real HTTP Basic or
 * {@code @WithMockUser}; these helpers mirror the identity the filters would
 * have produced so the transaction-boundary authorization checks can be
 * exercised without a request.
 */
public final class TestActors {

    private TestActors() {
    }

    public static void as(String username, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .map(org.springframework.security.core.GrantedAuthority.class::cast)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", authorities));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }

    public static <T> T call(String username, String role, Supplier<T> action) {
        as(username, role);
        try {
            return action.get();
        } finally {
            clear();
        }
    }

    public static void run(String username, String role, Runnable action) {
        as(username, role);
        try {
            action.run();
        } finally {
            clear();
        }
    }
}
