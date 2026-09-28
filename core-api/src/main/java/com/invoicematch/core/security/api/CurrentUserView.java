package com.invoicematch.core.security.api;

import java.util.List;

/**
 * Read-only view of the authenticated principal for the UI: the authoritative
 * login name and the business roles attached to it. It is derived server-side
 * from the security context and never from request input, so the UI can route
 * and enable actions without re-deriving identity.
 */
public record CurrentUserView(String username, List<String> roles) {

    public CurrentUserView {
        roles = List.copyOf(roles);
    }
}
