package com.invoicematch.core.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.matching.application.AppliedMapping;
import com.invoicematch.core.shared.domain.NumericOverflowException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of the canonical review snapshot payload: content, checked
 * amount recomputation and invariance under collection ordering. No Spring, no
 * database.
 */
class ReviewSnapshotPayloadBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BUNDLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESULT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final ReviewSnapshotPayloadBuilder builder = new ReviewSnapshotPayloadBuilder();

    @Test
    void canonicalPayloadContainsFrozenSubjectAndRecomputesAmounts() throws Exception {
        ReviewSnapshotPayloadInput input = input(
                List.of(
                        new AppliedMapping(1, "ITEM-A4-80", "POL-1001-1"),
                        new AppliedMapping(2, "ITEM-TONER-BK", "POL-1001-2")),
                List.of(
                        line(1, "A4 Paper", 60, 2500),
                        line(2, "Toner", 2, 55000)));

        ReviewSnapshotPayloadBuilder.CanonicalPayload canonical = builder.canonicalize(input);
        JsonNode payload = MAPPER.readTree(canonical.json());

        assertThat(payload.get("schemaVersion").asText()).isEqualTo("review-snapshot-v2");
        assertThat(payload.get("caseId").asText()).isEqualTo(CASE_ID.toString());
        assertThat(payload.get("caseVersion").asLong()).isEqualTo(7L);
        assertThat(payload.get("evidenceBundle").get("id").asText()).isEqualTo(BUNDLE_ID.toString());
        assertThat(payload.get("evidenceBundle").get("version").asInt()).isEqualTo(2);
        assertThat(payload.get("evidenceBundle").get("payloadHash").asText()).isEqualTo("bundle-hash");
        assertThat(payload.get("matchResult").get("id").asText()).isEqualTo(RESULT_ID.toString());
        assertThat(payload.get("matchResult").get("resultNumber").asInt()).isEqualTo(4);
        assertThat(payload.get("matchResult").get("mappingWatermark").asInt()).isEqualTo(2);
        assertThat(payload.get("matchResult").get("payload").get("lineOutcomes").isArray()).isTrue();
        assertThat(payload.get("effectiveMappings")).hasSize(2);
        assertThat(payload.get("effectiveMappings").get(1).get("itemId").asText()).isEqualTo("ITEM-TONER-BK");
        assertThat(payload.get("effectiveMappings").get(1).get("purchaseOrderLineId").asText())
                .isEqualTo("POL-1001-2");
        assertThat(payload.get("invoiceLines").get(0).get("lineAmount").asLong()).isEqualTo(150000L);
        assertThat(payload.get("invoiceLines").get(1).get("lineAmount").asLong()).isEqualTo(110000L);
        assertThat(payload.get("totalAmount").asLong()).isEqualTo(260000L);
        assertThat(payload.get("purchasingSnapshot").get("snapshotVersion").asLong()).isEqualTo(5L);
        assertThat(payload.get("purchasingSnapshot").get("purchaseOrderVersion").asLong()).isEqualTo(3L);
        assertThat(payload.get("purchasingSnapshot").get("payloadHash").asText()).isEqualTo("purchasing-hash");
        assertThat(canonical.hash()).hasSize(64);
    }

    @Test
    void canonicalHashIsInvariantToMappingAndLineOrdering() {
        List<AppliedMapping> mappings = List.of(
                new AppliedMapping(1, "ITEM-A4-80", "POL-1001-1"),
                new AppliedMapping(2, "ITEM-TONER-BK", "POL-1001-2"));
        List<EvidenceBundlePayload.EvidenceLine> lines = List.of(
                line(1, "A4 Paper", 60, 2500),
                line(2, "Toner", 2, 55000));

        ReviewSnapshotPayloadBuilder.CanonicalPayload first =
                builder.canonicalize(input(mappings, lines));

        List<AppliedMapping> shuffledMappings = new ArrayList<>(mappings);
        Collections.reverse(shuffledMappings);
        List<EvidenceBundlePayload.EvidenceLine> shuffledLines = new ArrayList<>(lines);
        Collections.reverse(shuffledLines);

        ReviewSnapshotPayloadBuilder.CanonicalPayload second =
                builder.canonicalize(input(shuffledMappings, shuffledLines));

        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.hash()).isEqualTo(first.hash());
    }

    @Test
    void v2CanonicalHashIgnoresNestedObjectKeyOrder() {
        ReviewSnapshotPayloadInput first = inputWithMatchPayload(
                "{\"lineOutcomes\":[{\"a\":1,\"b\":2}],\"schemaVersion\":\"match-result-v3\"}");
        ReviewSnapshotPayloadInput second = inputWithMatchPayload(
                "{\"schemaVersion\":\"match-result-v3\",\"lineOutcomes\":[{\"b\":2,\"a\":1}]}");

        ReviewSnapshotPayloadBuilder.CanonicalPayload firstV2 = builder.canonicalize(first);
        ReviewSnapshotPayloadBuilder.CanonicalPayload secondV2 = builder.canonicalize(second);

        // v2 recursively sorts object keys, so the same semantics hash equally
        // whether the match payload was held in memory or reloaded from jsonb.
        assertThat(secondV2.json()).isEqualTo(firstV2.json());
        assertThat(secondV2.hash()).isEqualTo(firstV2.hash());

        // The legacy v1 algorithm serializes the payload as received, so the same
        // semantic input hashes differently there; a v1 snapshot is never
        // verified with the v2 rule and vice versa.
        assertThat(builder.canonicalize(second, ReviewSnapshotPayloadBuilder.LEGACY_SCHEMA_VERSION).hash())
                .isNotEqualTo(builder.canonicalize(first, ReviewSnapshotPayloadBuilder.LEGACY_SCHEMA_VERSION).hash());
    }

    @Test
    void v2CanonicalHashPreservesArrayOrder() {
        ReviewSnapshotPayloadInput ordered = inputWithMatchPayload(
                "{\"schemaVersion\":\"match-result-v3\",\"lineOutcomes\":[{\"lineNumber\":1},{\"lineNumber\":2}]}");
        ReviewSnapshotPayloadInput reversed = inputWithMatchPayload(
                "{\"schemaVersion\":\"match-result-v3\",\"lineOutcomes\":[{\"lineNumber\":2},{\"lineNumber\":1}]}");

        assertThat(builder.canonicalize(reversed).hash()).isNotEqualTo(builder.canonicalize(ordered).hash());
    }

    @Test
    void unknownSchemaVersionIsRejected() {
        ReviewSnapshotPayloadInput input = input(List.of(), List.of(line(1, "A4 Paper", 5, 2500)));

        assertThatThrownBy(() -> builder.canonicalize(input, "review-snapshot-v9"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowingTotalIsRejectedAndNeverWrapped() {
        ReviewSnapshotPayloadInput input = input(
                List.of(),
                List.of(
                        line(1, "Expensive", 2, Long.MAX_VALUE),
                        line(2, "More", 1, Long.MAX_VALUE)));

        assertThatThrownBy(() -> builder.canonicalize(input))
                .isInstanceOf(NumericOverflowException.class);
    }

    @Test
    void optionalProposalBindsExactIdentityAndHashesWithoutChangingAmounts() throws Exception {
        var original = input(List.of(), List.of(line(1, "A4 Paper", 60, 2500)));
        var proof = new ProposalEvidenceReader.Reference(UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "a".repeat(64), "b".repeat(64));
        var legacy = builder.canonicalize(original);
        var selected = builder.canonicalize(original.withProposal(proof));
        var payload = MAPPER.readTree(selected.json());
        assertThat(payload.path("proposal").path("id").asText()).isEqualTo(proof.id().toString());
        assertThat(payload.path("proposal").path("payloadHash").asText()).isEqualTo(proof.payloadHash());
        assertThat(payload.path("proposal").path("contextHash").asText()).isEqualTo(proof.contextHash());
        assertThat(payload.path("totalAmount").asLong()).isEqualTo(150000L);
        assertThat(selected.hash()).isNotEqualTo(legacy.hash());
        assertThat(builder.canonicalize(original.withProposal(null))).isEqualTo(legacy);
        assertThat(builder.canonicalize(original.withProposal(new ProposalEvidenceReader.Reference(
                proof.id(), "c".repeat(64), proof.contextHash()))).hash()).isNotEqualTo(selected.hash());
        assertThat(builder.canonicalize(original.withProposal(new ProposalEvidenceReader.Reference(
                UUID.randomUUID(), proof.payloadHash(), proof.contextHash()))).hash()).isNotEqualTo(selected.hash());
        assertThat(builder.canonicalize(original.withProposal(new ProposalEvidenceReader.Reference(
                proof.id(), proof.payloadHash(), "d".repeat(64)))).hash()).isNotEqualTo(selected.hash());
        assertThatThrownBy(() -> builder.canonicalize(original.withProposal(proof),
                ReviewSnapshotPayloadBuilder.LEGACY_SCHEMA_VERSION)).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.canonicalize(original.withProposal(null), ReviewSnapshotPayloadBuilder.LEGACY_SCHEMA_VERSION))
                .isEqualTo(builder.canonicalize(original, ReviewSnapshotPayloadBuilder.LEGACY_SCHEMA_VERSION));
    }

    private static ReviewSnapshotPayloadInput input(
            List<AppliedMapping> mappings, List<EvidenceBundlePayload.EvidenceLine> lines) {
        return inputWithMatchPayload(
                "{\"schemaVersion\":\"match-result-v3\",\"lineOutcomes\":[],\"appliedMappings\":[]}", mappings, lines);
    }

    private static ReviewSnapshotPayloadInput inputWithMatchPayload(String matchPayload) {
        return inputWithMatchPayload(matchPayload, List.of(), List.of());
    }

    private static ReviewSnapshotPayloadInput inputWithMatchPayload(
            String matchPayload, List<AppliedMapping> mappings, List<EvidenceBundlePayload.EvidenceLine> lines) {
        return new ReviewSnapshotPayloadInput(
                CASE_ID,
                7L,
                BUNDLE_ID,
                2,
                "bundle-hash",
                RESULT_ID,
                4,
                "result-hash",
                2,
                matchPayload,
                mappings,
                lines,
                5L,
                3L,
                "purchasing-hash");
    }

    private static EvidenceBundlePayload.EvidenceLine line(
            int lineNumber, String rawItemName, int quantity, long unitPrice) {
        return new EvidenceBundlePayload.EvidenceLine(lineNumber, rawItemName, quantity, unitPrice, null);
    }
}
