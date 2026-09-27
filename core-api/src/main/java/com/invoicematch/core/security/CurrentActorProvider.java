package com.invoicematch.core.security;

import java.util.Objects;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the current {@link Actor} from Spring Security's context.
 *
 * <p>A request to a protected endpoint always carries an authenticated
 * principal, so its actor is the real login name and roles. Non-HTTP code (a
 * background worker, or a direct service call from a test) has no context and is
 * attributed to {@link Actor#system()}, which holds no business role.
 */
@Component
public class CurrentActorProvider {

    public Actor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Actor.system();
        }
        Objects.requireNonNull(authentication.getName(), "authentication name");
        var roles = authentication.getAuthorities().stream()
                .map(authority -> Role.fromAuthority(authority.getAuthority()))
                .flatMap(java.util.Optional::stream)
                .collect(java.util.stream.Collectors.toCollection(() -> java.util.EnumSet.noneOf(Role.class)));
        return new Actor(authentication.getName(), roles);
    }
}
