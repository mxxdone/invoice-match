package com.invoicematch.core.document;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import com.invoicematch.core.document.application.DocumentFailure;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.domain.UploadIntent;
import com.invoicematch.core.document.infrastructure.DocumentStorageProperties;
import com.invoicematch.core.document.infrastructure.MinioDocumentStorage;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentStorageTest {
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
    }
}
