package com.invoicematch.core.payment.domain;

import com.invoicematch.core.approval.domain.PaymentRequest;
import com.invoicematch.core.shared.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Canonical payment-export payload. It is fully derived from the immutable
 * {@link PaymentRequest} (plus the constant export version and event type) and
 * contains no credentials. The byte-for-byte text is mirrored by the
 * {@code payment_export_canonical_payload} SQL function so the outbox trigger
 * can bind every row to the exact payment and reject raw-SQL substitution.
 *
 * <p>Key order is fixed (alphabetical) with no whitespace and unquoted numbers.
 * Every string value is escaped exactly like PostgreSQL's
 * {@code to_jsonb(text)::text} output (quotes and backslashes, the short
 * backspace/form-feed/newline/CR/tab escapes, lowercase U+00xx escapes for
 * other control characters, and raw UTF-8 for everything else), so the Java and
 * SQL canonical text and
 * hash are byte-identical. Any change here must be made in lock-step with V8.
 */
public record PaymentExportPayload(
        UUID paymentRequestId,
        UUID invoiceCaseId,
        String purchaseOrderId,
        UUID reviewSnapshotId,
        String reviewPayloadHash,
        String externalRequestKey,
        long amount,
        String currency,
        long exportVersion) {

    public static final String EVENT_TYPE = "PaymentRequestExportRequested";
    private static final int MAX_CANONICAL_BYTES = 4096;

    public static PaymentExportPayload forPaymentRequest(PaymentRequest paymentRequest) {
        return new PaymentExportPayload(
                paymentRequest.id(),
                paymentRequest.invoiceCaseId(),
                paymentRequest.purchaseOrderId(),
                paymentRequest.reviewSnapshotId(),
                paymentRequest.reviewPayloadHash(),
                paymentRequest.externalRequestKey(),
                paymentRequest.amount().amount(),
                paymentRequest.currency(),
                paymentRequest.exportVersion());
    }

    /** Same as {@link #forPaymentRequest} for callers that only hold stored columns. */
    public static PaymentExportPayload forPaymentRequestFields(
            UUID paymentRequestId,
            UUID invoiceCaseId,
            String purchaseOrderId,
            UUID reviewSnapshotId,
            String reviewPayloadHash,
            String externalRequestKey,
            long amount,
            String currency,
            long exportVersion) {
        return new PaymentExportPayload(
                paymentRequestId,
                invoiceCaseId,
                purchaseOrderId,
                reviewSnapshotId,
                reviewPayloadHash,
                externalRequestKey,
                amount,
                currency,
                exportVersion);
    }

    public String idempotencyKey() {
        return paymentRequestId + ":" + exportVersion;
    }

    public String canonicalJson() {
        String json = "{\"amount\":" + amount
                + ",\"currency\":" + jsonString(currency)
                + ",\"eventType\":" + jsonString(EVENT_TYPE)
                + ",\"exportVersion\":" + exportVersion
                + ",\"externalRequestKey\":" + jsonString(externalRequestKey)
                + ",\"idempotencyKey\":" + jsonString(idempotencyKey())
                + ",\"invoiceCaseId\":" + jsonString(invoiceCaseId.toString())
                + ",\"paymentRequestId\":" + jsonString(paymentRequestId.toString())
                + ",\"purchaseOrderId\":" + jsonString(purchaseOrderId)
                + ",\"reviewPayloadHash\":" + jsonString(reviewPayloadHash)
                + ",\"reviewSnapshotId\":" + jsonString(reviewSnapshotId.toString())
                + "}";
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_BYTES) {
            throw new DomainValidationException("payment export payload exceeds " + MAX_CANONICAL_BYTES + " bytes");
        }
        return json;
    }

    /**
     * Renders a JSON string exactly like PostgreSQL's {@code to_jsonb(text)}.
     * PostgreSQL escapes {@code "} and backslash, uses the short escapes for
     * backspace/form feed/newline/CR/tab, emits lowercase U+00xx escapes for
     * every other code point below U+0020, and passes all other characters
     * (including Unicode) through as raw UTF-8.
     */
    static String jsonString(String value) {
        rejectUnpairedSurrogates(value);
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /**
     * A Java String may hold an unpaired UTF-16 surrogate, which cannot be
     * encoded to UTF-8 and for which Java and PostgreSQL could disagree. Such a
     * value is rejected explicitly instead of silently emitting broken bytes.
     */
    static void rejectUnpairedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new DomainValidationException("payment export payload contains an unpaired surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new DomainValidationException("payment export payload contains an unpaired surrogate");
            }
        }
    }

    public String payloadHash() {
        return sha256Hex(canonicalJson());
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
