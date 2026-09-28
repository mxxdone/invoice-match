package com.invoicematch.core.payment.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies the shared-secret HMAC signature of a mock-ERP webhook.
 *
 * <p>The signed message is {@code "<timestamp>.<raw body>"} and the header is
 * {@code X-Mock-Erp-Signature: sha256=<lowercase hex hmac>}. Verification is
 * timing-safe ({@link MessageDigest#isEqual}) and enforces a bounded timestamp
 * tolerance so a captured request cannot be replayed indefinitely. A blank
 * configured secret fails closed. The secret is never logged.
 */
@Component
public class MockErpSignatureVerifier {

    public static final String SIGNATURE_HEADER = "X-Mock-Erp-Signature";
    public static final String TIMESTAMP_HEADER = "X-Mock-Erp-Timestamp";
    private static final String PREFIX = "sha256=";
    private static final Logger log = LoggerFactory.getLogger(MockErpSignatureVerifier.class);

    private final MockErpWebhookProperties properties;
    private final Clock clock;

    public MockErpSignatureVerifier(MockErpWebhookProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Verifies the signature over {@code rawBody}. Any failure throws
     * {@link WebhookSignatureException}; the caller must not parse or apply the
     * body before this returns normally.
     */
    public void verify(String timestampHeader, String signatureHeader, String rawBody) {
        if (!properties.hasSecret()) {
            log.warn("mock-ERP webhook secret is not configured; rejecting every result webhook");
            throw new WebhookSignatureException("signature verification is not configured");
        }
        if (timestampHeader == null || timestampHeader.isBlank()
                || signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookSignatureException("missing signature headers");
        }
        String timestamp = timestampHeader.trim();
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new WebhookSignatureException("malformed signature timestamp");
        }
        long now = clock.instant().getEpochSecond();
        long tolerance = properties.signatureTolerance().toSeconds();
        if (Math.abs(now - sentAt) > tolerance) {
            throw new WebhookSignatureException("signature is outside the accepted time window");
        }
        if (!signatureHeader.startsWith(PREFIX)) {
            throw new WebhookSignatureException("unsupported signature algorithm");
        }
        String provided = signatureHeader.substring(PREFIX.length());
        String expected = hexHmac(properties.secret(), timestamp + "." + rawBody);
        if (!MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new WebhookSignatureException("signature does not match");
        }
    }

    private static String hexHmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
