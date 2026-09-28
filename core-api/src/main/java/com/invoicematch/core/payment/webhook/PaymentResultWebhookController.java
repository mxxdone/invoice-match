package com.invoicematch.core.payment.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives signed mock-ERP payment results (Spec 18).
 *
 * <p>The body is signature-verified before it is parsed, so unauthenticated
 * bytes can never reach the application service. The endpoint is intentionally
 * outside {@code /api/**}: it is machine-to-machine and authenticates with the
 * shared-secret HMAC, not HTTP Basic.
 */
@RestController
public class PaymentResultWebhookController {

    public static final String PATH = "/webhooks/mock-erp/payment-results";
    private static final int MAX_FIELD_LENGTH = 200;
    private static final Logger log = LoggerFactory.getLogger(PaymentResultWebhookController.class);

    private final MockErpSignatureVerifier verifier;
    private final MockErpWebhookProperties properties;
    private final PaymentResultWebhookApplicationService applicationService;
    private final ObjectMapper objectMapper;

    public PaymentResultWebhookController(
            MockErpSignatureVerifier verifier,
            MockErpWebhookProperties properties,
            PaymentResultWebhookApplicationService applicationService,
            ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.properties = properties;
        this.applicationService = applicationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(PATH)
    public ResponseEntity<PaymentResultWebhookResponse> receive(
            @RequestHeader(value = MockErpSignatureVerifier.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = MockErpSignatureVerifier.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody(required = false) String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new PaymentResultValidationException("request body is required");
        }
        verifier.verify(timestamp, signature, rawBody);

        PaymentResultWebhookRequest request = parse(rawBody);
        requireText(request.externalEventId(), "externalEventId");
        requireText(request.externalPaymentKey(), "externalPaymentKey");
        if (!properties.provider().equals(request.provider())) {
            throw new PaymentResultValidationException("unsupported provider");
        }
        UUID paymentRequestId = parsePaymentRequestId(request.paymentRequestId());
        PaymentResultOutcome outcome = PaymentResultOutcome.parse(request.outcome());
        if (request.externalReference() != null && request.externalReference().length() > MAX_FIELD_LENGTH) {
            throw new PaymentResultValidationException("externalReference is too long");
        }

        PaymentResultIngestResult result = applicationService.ingest(new PaymentResultCommand(
                properties.provider(),
                request.externalEventId().trim(),
                request.externalPaymentKey().trim(),
                paymentRequestId,
                outcome,
                sha256Hex(rawBody),
                request.externalReference()));
        log.debug("accepted mock-ERP payment result: {}", result);
        return ResponseEntity.ok(new PaymentResultWebhookResponse(result.name()));
    }

    private PaymentResultWebhookRequest parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, PaymentResultWebhookRequest.class);
        } catch (JsonProcessingException e) {
            throw new PaymentResultValidationException("request body is not valid JSON");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PaymentResultValidationException(field + " is required");
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new PaymentResultValidationException(field + " is too long");
        }
    }

    private static UUID parsePaymentRequestId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new PaymentResultValidationException("paymentRequestId is not a UUID");
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
