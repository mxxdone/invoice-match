package com.invoicematch.core.security;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The authenticated actor of one business write: the principal name and the
 * business roles derived from its granted authorities. The name is the
 * authoritative identity used for {@code submittedBy}, review decisions and
 * audit; it is never taken from the request body.
 */
public record Actor(String username, Set<Role> roles) {

    public Actor {
        Objects.requireNonNull(username, "username");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    /**
     * Internal, non-HTTP writes (background work and direct service calls in
     * tests) have no authenticated principal. They are attributed to a system
     * actor without business roles, so they can never satisfy an approver or
     * self-approval rule that requires a real role.
     */
    public static Actor system() {
        return new Actor("system", Set.of());
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /** Canonical, ordinal-sorted role list used for the audit {@code actor_roles}. */
    public String rolesCsv() {
        return new TreeSet<>(roles).stream()
                .map(Role::name)
                .collect(Collectors.joining(","));
    }
}
