package com.invoicematch.core.document.application;

import com.invoicematch.core.document.domain.UploadIntent;

public interface DocumentStorage {
    String presign(UploadIntent upload);
    byte[] readVerified(UploadIntent upload);
    byte[] readOriginal(DocumentOriginalRequest request);
    void writeOriginal(String key, byte[] bytes, String mediaType);
    void removeOriginal(String key);
    void removeTemporary(java.util.UUID caseId,java.util.UUID uploadId);
    SignedDownload presignDownload(DocumentDownloadRequest request);
}
