package com.invoicematch.core.payment.webhook;

/** External ERP result outcome. FAILED and RESULT_UNKNOWN remain distinct. */
public enum PaymentResultOutcome {
    ACKNOWLEDGED,
    FAILED;

    public static PaymentResultOutcome parse(String value) {
        if (value == null || value.isBlank()) {
            throw new PaymentResultValidationException("outcome is required");
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new PaymentResultValidationException("unsupported outcome");
        }
    }
}
