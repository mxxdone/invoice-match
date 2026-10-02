package com.invoicematch.core.document;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import com.invoicematch.core.document.application.DocumentDownloadRequest;
import com.invoicematch.core.document.application.DocumentFailure;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.application.SignedDownload;
import com.invoicematch.core.document.domain.UploadIntent;
import com.invoicematch.core.document.infrastructure.DocumentStorageProperties;
import com.invoicematch.core.document.infrastructure.MinioDocumentStorage;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentStorageTest {
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test void signsPublicEndpointWithoutCallingUnreachableInternalStorage() {
        var storage = new MinioDocumentStorage(new DocumentStorageProperties(true, "http://invalid.internal:9000",
                "http://browser.example:19000", "unit-access", UUID.randomUUID().toString(), "invoice-documents", "us-east-1"));
        var upload = new UploadIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "invoice.pdf",
                DocumentPolicy.PDF, 6, "0".repeat(64), "uploads/unit", Instant.now().plusSeconds(600), Instant.now());
        String url = assertTimeout(Duration.ofSeconds(3), () -> storage.presign(upload));
        assertThat(URI.create(url).getHost()).isEqualTo("browser.example");
        assertThat(URI.create(url).getPort()).isEqualTo(19000);
    }

    @Test void disabledStorageFailsClosedWithoutRequiringCredentialsAtStartup() {
        var storage = new MinioDocumentStorage(new DocumentStorageProperties(false,null,null,null,null,null,null));
        assertThatThrownBy(() -> storage.presign(null)).isInstanceOf(DocumentFailure.class)
                .satisfies(e -> assertThat(((DocumentFailure)e).status()).isEqualTo(503));
        assertThatThrownBy(() -> storage.presignDownload(null)).isInstanceOf(DocumentFailure.class)
                .satisfies(e -> assertThat(((DocumentFailure)e).status()).isEqualTo(503));
    }

    @Test void signsPublicEndpointForDownloadWithTrustedTypeSafeDispositionAndMatchingExpiry() {
        var storage = new MinioDocumentStorage(new DocumentStorageProperties(true, "http://invalid.internal:9000",
                "http://browser.example:19000", "unit-access", UUID.randomUUID().toString(), "invoice-documents", "us-east-1"));
        var request = new DocumentDownloadRequest("originals/case/doc/object", "청구 \"서\".pdf",
                DocumentPolicy.PDF, DocumentPolicy.INLINE, DocumentPolicy.DOWNLOAD_TTL_SECONDS);
        SignedDownload signed = assertTimeout(Duration.ofSeconds(3), () -> storage.presignDownload(request));
        URI uri = URI.create(signed.url());
        assertThat(uri.getHost()).isEqualTo("browser.example");
        assertThat(uri.getPort()).isEqualTo(19000);
        Map<String, String> query = query(uri);
        assertThat(query).containsEntry("X-Amz-Expires", "120");
        assertThat(query).containsEntry("response-content-type", DocumentPolicy.PDF);
        // The adapter owns the wire format: printable-ASCII fallback plus a
        // UTF-8 filename* that carries the unicode name and escapes the quotes.
        assertThat(query.get("response-content-disposition"))
                .startsWith("inline; filename=\"")
                .contains("__ ___.pdf")
                .contains("filename*=UTF-8''%EC%B2%AD%EA%B5%AC%20%22%EC%84%9C%22.pdf")
                .doesNotContain("\r", "\n");
        // The reported expiry is read from the URL's own signing time + lifetime.
        Instant fromUrl = Instant.from(AMZ_DATE.parse(query.get("X-Amz-Date")))
                .plusSeconds(Long.parseLong(query.get("X-Amz-Expires")));
        assertThat(signed.expiresAt()).isEqualTo(fromUrl);
    }

    @Test void signingFailureIsRedactedToAGeneric503() {
        var secret = UUID.randomUUID().toString();
        var storage = new MinioDocumentStorage(new DocumentStorageProperties(true, "http://invalid.internal:9000",
                "http://browser.example:19000", "unit-access", secret, "invoice-documents", "us-east-1"));
        var broken = new DocumentDownloadRequest(null, "invoice.pdf", DocumentPolicy.PDF, DocumentPolicy.ATTACHMENT,
                DocumentPolicy.DOWNLOAD_TTL_SECONDS);
        assertThatThrownBy(() -> storage.presignDownload(broken)).isInstanceOf(DocumentFailure.class)
                .satisfies(e -> {
                    var failure = (DocumentFailure) e;
                    assertThat(failure.status()).isEqualTo(503);
                    assertThat(failure.code()).isEqualTo("DOCUMENT_STORAGE_UNAVAILABLE");
                    assertThat(failure.getMessage()).doesNotContain(secret, "unit-access");
                });
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
