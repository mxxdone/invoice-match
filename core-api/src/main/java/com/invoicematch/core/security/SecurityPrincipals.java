package com.invoicematch.core.security;

/**
 * Reserved identity constants.
 *
 * <p>{@link #RESERVED} is used to attribute rows that have no authenticated
 * creator (pre-P1-06 backfill). It is deliberately impossible to log in as: the
 * demo user configuration is validated to reject it, and no incoming HTTP
 * principal can be constructed with it because the name comes from the
 * authenticated {@code UserDetails}, not the request.
 */
public final class SecurityPrincipals {

    public static final String RESERVED = "__reserved__";
    public static final String SYSTEM = "system";

    private SecurityPrincipals() {
    }
}
