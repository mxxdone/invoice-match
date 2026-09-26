package com.invoicematch.core.invoicecase.application;

/**
 * Normalizes a supplier invoice number for duplicate detection: uppercase and
 * remove every non-alphanumeric character, so {@code INV-2026-001} and
 * {@code inv 2026 001} compare equal.
 */
public final class InvoiceNumberNormalizer {

    private InvoiceNumberNormalizer() {
    }

    public static String normalize(String invoiceNumber) {
        if (invoiceNumber == null) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(invoiceNumber.length());
        for (int i = 0; i < invoiceNumber.length(); i++) {
            char c = invoiceNumber.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toUpperCase(c));
            }
        }
        return normalized.toString();
    }
}
