package com.invoicematch.core.analysis.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * Owns the machine-worker authentication policy: whether the internal surface is
 * enabled and whether a presented Bearer secret matches the configured one. The
 * HTTP filter only extracts the Bearer value and maps a successful check to a
 * machine principal; all enable/secret policy lives here.
 *
 * <p>Fail closed: a disabled surface, a missing/short configured secret or a
 * blank presented secret never authenticates. The comparison hashes both values
 * to a fixed length and uses {@link MessageDigest#isEqual}, so it does not branch
 * on the secret bytes.
 */
@Component
public class AnalysisWorkerAuthenticator {

    /** Authority granted to an authenticated machine worker. */
    public static final String AUTHORITY = "ROLE_ANALYSIS_WORKER";
    /** Principal name of an authenticated machine worker. */
    public static final String PRINCIPAL_NAME = "analysis-worker";

    private final AnalysisWorkerProperties properties;

    public AnalysisWorkerAuthenticator(AnalysisWorkerProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    /** True only for an enabled surface with a valid configured secret and a match. */
    public boolean matches(String presentedToken) {
        if (!properties.enabled()) {
            return false;
        }
        String configured = properties.token();
        if (configured == null || configured.length() < AnalysisWorkerProperties.MIN_TOKEN_LENGTH) {
            return false;
        }
        if (presentedToken == null || presentedToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(digest(presentedToken), digest(configured));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
