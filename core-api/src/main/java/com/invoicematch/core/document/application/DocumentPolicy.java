package com.invoicematch.core.document.application;

import com.invoicematch.core.shared.domain.DomainValidationException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class DocumentPolicy {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_FILES = 10;
    public static final int TTL_SECONDS = 600;
    public static final int DOWNLOAD_TTL_SECONDS = 120;
    public static final String ATTACHMENT = "attachment";
    public static final String INLINE = "inline";
    public static final String PDF = "application/pdf";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private DocumentPolicy() { }
    /**
     * Only an absent parameter defaults to attachment; an explicit empty,
     * blank or unknown value is a 400 before any document lookup. The HTTP
     * header serialization of the chosen value belongs to the storage adapter.
     */
    public static String disposition(String raw) {
        if (raw == null) {
            return ATTACHMENT;
        }
        if (ATTACHMENT.equals(raw) || INLINE.equals(raw)) {
            return raw;
        }
        throw new DocumentFailure(400, "INVALID_DISPOSITION", "disposition must be attachment or inline");
    }
    /** Inline rendering is restricted to PDF; XLSX keeps the download-only path. */
    public static void requireInlineSupported(String disposition, String mediaType) {
        if (INLINE.equals(disposition) && !PDF.equals(mediaType)) {
            throw new DocumentFailure(400, "INVALID_DISPOSITION", "inline is only supported for PDF");
        }
    }
    public static void request(String requestId, Long version) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128 || version == null || version < 0) {
            throw new DomainValidationException("requestId and nonnegative expectedCaseVersion are required");
        }
    }
    public static void checksum(String checksum) {
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new DomainValidationException("checksum must be lowercase SHA-256 hex");
        }
    }
    public static void metadata(String name, String mediaType, Long size, String checksum) {
        checksum(checksum);
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException("fileName must be a plain name of at most 255 characters");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(PDF.equals(mediaType) && lower.endsWith(".pdf"))
                && !(XLSX.equals(mediaType) && lower.endsWith(".xlsx"))) {
            throw new DomainValidationException("Only matching PDF and XLSX extensions/media types are supported");
        }
        if (size == null || size <= 0 || size > MAX_BYTES) {
            throw new DomainValidationException("sizeBytes must be between 1 and 10485760");
        }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void bytes(byte[] bytes, String mediaType, long size, String checksum) {
        boolean signature = PDF.equals(mediaType)
                ? bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-'
                : bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        if (bytes.length != size || !hash(bytes).equals(checksum) || !signature) {
            throw new DocumentFailure(400, "DOCUMENT_CONTENT_MISMATCH", "Object size, checksum or format signature does not match");
        }
    }
}
