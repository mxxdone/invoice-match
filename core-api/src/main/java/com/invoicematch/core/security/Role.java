package com.invoicematch.core.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three Phase 1 business roles. {@code SUBMITTER} owns the claim lifecycle,
 * {@code APPROVER} owns the review/approval decision, and {@code OPERATOR} owns
 * deterministic reprocessing and diagnostic reads. The separation exists so a
 * single person cannot both submit and approve a claim.
 */
public enum Role {
    SUBMITTER,
    APPROVER,
    OPERATOR;

    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Resolves a Spring Security authority string such as {@code ROLE_APPROVER}
     * back to a {@link Role}. Unknown authorities are ignored so client-supplied
     * or framework authorities never grant a business role by accident.
     */
    public static Optional<Role> fromAuthority(String authority) {
        if (authority == null || !authority.startsWith("ROLE_")) {
            return Optional.empty();
        }
        String name = authority.substring("ROLE_".length());
        return Arrays.stream(values()).filter(role -> role.name().equals(name)).findFirst();
    }
}
