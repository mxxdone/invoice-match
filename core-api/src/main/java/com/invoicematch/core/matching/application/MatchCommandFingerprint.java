package com.invoicematch.core.matching.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Canonical SHA-256 fingerprint of a match request's business payload. Only the
 * case id is part of the payload: a match is fully determined by which case
 * (and therefore which latest frozen bundle and current purchasing snapshot) it
 * runs for. Re-matching with a new request id always creates a new append-only
 * result.
 */
@Component
public class MatchCommandFingerprint {

    public String runMatch(RunMatchCommand command) {
        return sha256Hex(command.caseId().toString());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
