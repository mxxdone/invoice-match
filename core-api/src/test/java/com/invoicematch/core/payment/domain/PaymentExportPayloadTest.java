package com.invoicematch.core.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Java JSON-string escaper must match PostgreSQL's {@code to_jsonb(text)}
 * output byte for byte, because the outbox trigger hashes the SQL text and the
 * relay sends the Java text. The database parity itself is proven against a real
 * PostgreSQL function in the integration tests; this pins the escaping rules.
 */
class PaymentExportPayloadTest {

    @Test
    void escapesQuoteAndBackslash() {
        assertThat(PaymentExportPayload.jsonString("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }

    @Test
    void usesShortEscapesForCommonControlCharacters() {
        assertThat(PaymentExportPayload.jsonString("a\b\f\n\r\tb")).isEqualTo("\"a\\b\\f\\n\\r\\tb\"");
    }

    @Test
    void escapesOtherControlCharactersAsLowercaseFourHexDigitUnicode() {
        assertThat(PaymentExportPayload.jsonString("\u0000\u0001\u001f")).isEqualTo("\"\\u0000\\u0001\\u001f\"");
    }

    @Test
    void passesUnicodeThroughAsRawUtf8() {
        assertThat(PaymentExportPayload.jsonString("한글 😀 café")).isEqualTo("\"한글 😀 café\"");
    }

    @Test
    void rejectsUnpairedSurrogates() {
        String loneHigh = "a\uD83D" + "b";
        String loneLow = "a\uDE00b";
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> PaymentExportPayload.jsonString(loneHigh)))
                .isInstanceOf(com.invoicematch.core.shared.domain.DomainValidationException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> PaymentExportPayload.jsonString(loneLow)))
                .isInstanceOf(com.invoicematch.core.shared.domain.DomainValidationException.class);
        // A valid surrogate pair is still accepted.
        assertThat(PaymentExportPayload.jsonString("😀")).isEqualTo("\"😀\"");
    }

    @Test
    void canonicalJsonEmbedsTheEscapedPurchaseOrderIdInFixedKeyOrder() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID caseId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID snapshotId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        PaymentExportPayload payload = new PaymentExportPayload(
                paymentId, caseId, "PO-\"\\\n\u0001", snapshotId, "hash", "PAYMENT:" + caseId + ":" + snapshotId,
                150000L, "KRW", 1L);

        String json = payload.canonicalJson();
        assertThat(json)
                .startsWith("{\"amount\":150000,\"currency\":\"KRW\",\"eventType\":\"PaymentRequestExportRequested\"")
                .contains("\"purchaseOrderId\":\"PO-\\\"\\\\\\n\\u0001\"")
                .endsWith("\"reviewSnapshotId\":\"" + snapshotId + "\"}");
    }
}
