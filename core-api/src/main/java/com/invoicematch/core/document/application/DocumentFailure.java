package com.invoicematch.core.document.application;

public class DocumentFailure extends RuntimeException {
    private final int status;
    private final String code;
    public DocumentFailure(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static DocumentFailure storage() {
        return new DocumentFailure(503, "DOCUMENT_STORAGE_UNAVAILABLE", "Document storage is unavailable");
    }
}
