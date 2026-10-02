package com.invoicematch.core.document.infrastructure;

import com.invoicematch.core.document.application.DocumentFailure;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.application.DocumentStorage;
import com.invoicematch.core.document.domain.UploadIntent;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class MinioDocumentStorage implements DocumentStorage {
    private final MinioClient internal;
    private final MinioClient external;
    private final String bucket;
    public MinioDocumentStorage(DocumentStorageProperties p) {
        bucket = p.bucket();
        if (!p.enabled()) { internal = null; external = null; return; }
        if (p.accessKey() == null || p.accessKey().isBlank() || p.secretKey() == null || p.secretKey().isBlank()
                || p.region() == null || p.region().isBlank()) {
            throw new IllegalArgumentException("Enabled document storage requires credentials and a region");
        }
        OkHttpClient http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(20)).build();
        internal = MinioClient.builder().endpoint(p.endpoint()).credentials(p.accessKey(), p.secretKey())
                .region(p.region()).httpClient(http).build();
        external = MinioClient.builder().endpoint(p.publicEndpoint()).credentials(p.accessKey(), p.secretKey())
                .region(p.region()).httpClient(http).build();
    }
    private void enabled() { if (internal == null) throw DocumentFailure.storage(); }
    @Override public String presign(UploadIntent u) {
        enabled();
        try {
            // Fixed region makes signing local; never a bucket-region HTTP lookup while locked.
            return external.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT)
                    .bucket(bucket).object(u.uploadKey()).expiry(DocumentPolicy.TTL_SECONDS).build());
        } catch (Exception e) { throw DocumentFailure.storage(); }
    }
    @Override public byte[] readVerified(UploadIntent u) {
        enabled();
        try (var object = internal.getObject(GetObjectArgs.builder().bucket(bucket).object(u.uploadKey()).build())) {
            if (!u.mediaType().equals(object.headers().get("Content-Type"))) {
                throw new DocumentFailure(400, "DOCUMENT_CONTENT_MISMATCH", "Object Content-Type does not match");
            }
            byte[] bytes = object.readNBytes(DocumentPolicy.MAX_BYTES + 1);
            DocumentPolicy.bytes(bytes, u.mediaType(), u.sizeBytes(), u.checksum());
            return bytes;
        } catch (DocumentFailure e) { throw e; }
        catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new DocumentFailure(409, "DOCUMENT_NOT_UPLOADED", "Upload the object before completing");
            }
            throw DocumentFailure.storage();
        } catch (Exception e) { throw DocumentFailure.storage(); }
    }
    @Override public void writeOriginal(String key, byte[] bytes, String mediaType) {
        enabled();
        try {
            internal.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mediaType)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        } catch (Exception e) { throw DocumentFailure.storage(); }
    }
    @Override public void removeOriginal(String key) {
        enabled();
        try { internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception e) { throw DocumentFailure.storage(); }
    }
}
