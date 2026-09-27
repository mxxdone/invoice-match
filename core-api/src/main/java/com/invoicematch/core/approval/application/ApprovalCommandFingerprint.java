package com.invoicematch.core.approval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Canonical SHA-256 fingerprint of the business payload of an approval request,
 * excluding the request id itself. Two requests with the same request id and
 * fingerprint are replays; a different fingerprint is an actor-local conflict.
 * The actor is not part of the fingerprint: it is already part of the
 * idempotency key.
 */
@Component
public class ApprovalCommandFingerprint {

    private final ObjectMapper mapper = new ObjectMapper();

    public String approve(ApproveInvoiceCaseCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        node.put("reviewSnapshotId", command.reviewSnapshotId().toString());
        node.put("reviewPayloadHash", command.reviewPayloadHash());
        try {
            return sha256Hex(mapper.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Approval request fingerprint serialization failed", e);
        }
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
