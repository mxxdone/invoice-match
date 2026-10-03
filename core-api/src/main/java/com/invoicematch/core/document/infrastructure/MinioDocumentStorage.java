package com.invoicematch.core.document.infrastructure;

import com.invoicematch.core.document.application.DocumentDownloadRequest;
import com.invoicematch.core.document.application.DocumentOriginalRequest;
import com.invoicematch.core.document.application.DocumentFailure;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.application.DocumentStorage;
import com.invoicematch.core.document.application.SignedDownload;
import com.invoicematch.core.document.domain.UploadIntent;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
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
    @Override public SignedDownload presignDownload(DocumentDownloadRequest d) {
        enabled();
        try {
            // The browser uses the public endpoint. Fixed region makes signing
            // local; the server-derived filename/mediaType are bound into the
            // signature as response header overrides, so neither can be tampered
            // with after issuance. The application-chosen TTL is the signing TTL,
            // and the expiry reported back is read from the URL's own
            // X-Amz-Date + X-Amz-Expires so the DTO cannot drift from the link.
            String url = external.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                    .bucket(bucket).object(d.objectKey()).expiry(d.ttlSeconds())
                    .extraQueryParams(Map.of(
                            "response-content-disposition", contentDisposition(d.disposition(), d.fileName()),
                            "response-content-type", d.mediaType()))
                    .build());
            return new SignedDownload(url, signedExpiry(url));
        } catch (Exception e) { throw DocumentFailure.storage(); }
    }
    /**
     * RFC 6266/5987 response header value: a printable-ASCII fallback plus a
     * UTF-8 percent-encoded {@code filename*}. The server-derived, already
     * name-validated filename is the only input, so no request value is
     * reflected into a header.
     */
    static String contentDisposition(String disposition, String fileName) {
        return disposition + "; filename=\"" + asciiFallback(fileName) + "\"; filename*=UTF-8''" + rfc5987(fileName);
    }
    private static String asciiFallback(String fileName) {
        StringBuilder out = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            out.append(c >= 0x20 && c <= 0x7e && c != '"' && c != '\\' ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }
    private static String rfc5987(String fileName) {
        StringBuilder out = new StringBuilder();
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xff;
            if ((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')
                    || "!#$&+-.^_`|~".indexOf(v) >= 0) {
                out.append((char) v);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((v >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(v & 0xf, 16)));
            }
        }
        return out.toString();
    }
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static Instant signedExpiry(String url) {
        String date = null;
        String expires = null;
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = pair.substring(0, eq);
            if ("X-Amz-Date".equals(key)) {
                date = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            } else if ("X-Amz-Expires".equals(key)) {
                expires = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        if (date == null || expires == null) {
            throw new IllegalStateException("Signed URL is missing its expiry parameters");
        }
        return Instant.from(AMZ_DATE.parse(date)).plusSeconds(Long.parseLong(expires));
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
    @Override public byte[] readOriginal(DocumentOriginalRequest request) {
        enabled();
        try (var object = internal.getObject(GetObjectArgs.builder().bucket(bucket)
                .object(request.objectKey()).build())) {
            if (!request.mediaType().equals(object.headers().get("Content-Type"))
                    || request.sizeBytes() <= 0 || request.sizeBytes() > DocumentPolicy.MAX_BYTES) {
                throw DocumentFailure.storage();
            }
            byte[] bytes = object.readNBytes((int) request.sizeBytes() + 1);
            DocumentPolicy.bytes(bytes, request.mediaType(), request.sizeBytes(), request.checksum());
            return bytes;
        } catch (Exception e) { throw DocumentFailure.storage(); }
    }
    @Override public void writeOriginal(String key, byte[] bytes, String mediaType) {
        enabled();
        try {
            internal.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mediaType)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        } catch (Exception e) { throw DocumentFailure.storage(); }
    }
    @Override public void removeTemporary(java.util.UUID caseId, java.util.UUID uploadId) {
        enabled();
        String key="uploads/"+java.util.Objects.requireNonNull(caseId)+"/"+java.util.Objects.requireNonNull(uploadId);
        try { internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception e) { throw DocumentFailure.storage(); }
    }
    @Override public void removeOriginal(String key) {
        enabled();
        try { internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception e) { throw DocumentFailure.storage(); }
    }
}
