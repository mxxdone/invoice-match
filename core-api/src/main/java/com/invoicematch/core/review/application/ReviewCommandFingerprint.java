package com.invoicematch.core.review.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Canonical SHA-256 fingerprint of the business payload of a review write
 * request, excluding the request id itself. Two requests with the same request
 * id and fingerprint are replays; a different fingerprint is a conflict.
 */
@Component
public class ReviewCommandFingerprint {

    private final ObjectMapper mapper = new ObjectMapper();

    public String freezeSnapshot(FreezeReviewSnapshotCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        if(command.proposalId()!=null || command.proposalHash()!=null) {
            node.put("proposalId",command.proposalId()==null?null:command.proposalId().toString());node.put("proposalHash",command.proposalHash());
        }
        return sha256Hex(write(node));
    }

    public String recordMapping(RecordMappingDecisionCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        node.put("reviewSnapshotId", command.reviewSnapshotId().toString());
        node.put("reviewPayloadHash", command.reviewPayloadHash());
        node.put("lineNumber", command.lineNumber());
        node.put("itemId", command.itemId());
        return sha256Hex(write(node));
    }

    public String requestSupplement(RequestSupplementCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        node.put("reviewSnapshotId", command.reviewSnapshotId().toString());
        node.put("reviewPayloadHash", command.reviewPayloadHash());
        node.put("reason", command.reason());
        return sha256Hex(write(node));
    }

    public String reject(RejectReviewCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        node.put("reviewSnapshotId", command.reviewSnapshotId().toString());
        node.put("reviewPayloadHash", command.reviewPayloadHash());
        node.put("reason", command.reason());
        return sha256Hex(write(node));
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Request fingerprint serialization failed", e);
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
