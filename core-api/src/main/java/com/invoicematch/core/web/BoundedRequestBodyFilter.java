package com.invoicematch.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces a maximum request body size for body-bearing methods by reading the
 * body from the actual stream, so it applies to chunked requests with no
 * {@code Content-Length} as well as ones that declare a length. An oversized
 * request is rejected with {@code 413} before it reaches security, the
 * controller or any transaction, so it can have no business or audit effect.
 *
 * <p>The allowed body is cached and re-served to the rest of the chain; the cap
 * is small enough (default 256 KiB) that this is a bounded use of memory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BoundedRequestBodyFilter extends OncePerRequestFilter {

    public static final String MAX_BODY_BYTES_PROPERTY = "http.request.max-body-bytes";
    private static final byte[] TOO_LARGE_BODY =
            ("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body exceeds the configured limit\"}")
                    .getBytes(StandardCharsets.UTF_8);

    private final int maxBodyBytes;

    public BoundedRequestBodyFilter(
            @Value("${" + MAX_BODY_BYTES_PROPERTY + ":262144}") int maxBodyBytes) {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException(MAX_BODY_BYTES_PROPERTY + " must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return !(method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        byte[] body = readAtMost(request.getInputStream(), maxBodyBytes + 1L);
        if (body.length > maxBodyBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(TOO_LARGE_BODY);
            return;
        }
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private static byte[] readAtMost(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        while (total < limit) {
            int max = (int) Math.min(buffer.length, limit - total);
            int read = input.read(buffer, 0, max);
            if (read == -1) {
                break;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }
}
