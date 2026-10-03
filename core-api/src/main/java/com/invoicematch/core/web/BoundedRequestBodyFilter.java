package com.invoicematch.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
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
 * <p>The default cap is 256 KiB. The single machine result endpoint
 * ({@code POST /internal/analysis-runs/{uuid}/results}) carries a bounded
 * {@code document-parse-v1} JSON document and is allowed a larger cap of
 * 4 MiB + 64 KiB; every other route keeps the default. The body is cached and
 * re-served to the rest of the chain, so the cap is a bounded use of memory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BoundedRequestBodyFilter extends OncePerRequestFilter {

    public static final String MAX_BODY_BYTES_PROPERTY = "http.request.max-body-bytes";
    public static final String ANALYSIS_RESULT_MAX_BODY_BYTES_PROPERTY =
            "http.request.analysis-result-max-body-bytes";
    private static final Pattern ANALYSIS_RESULT_PATH =
            Pattern.compile("^/internal/analysis-runs/[0-9a-fA-F-]{36}/results$");
    private static final byte[] TOO_LARGE_BODY =
            ("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body exceeds the configured limit\"}")
                    .getBytes(StandardCharsets.UTF_8);

    private final int maxBodyBytes;
    private final int analysisResultMaxBodyBytes;

    public BoundedRequestBodyFilter(
            @Value("${" + MAX_BODY_BYTES_PROPERTY + ":262144}") int maxBodyBytes,
            @Value("${" + ANALYSIS_RESULT_MAX_BODY_BYTES_PROPERTY + ":4259840}") int analysisResultMaxBodyBytes) {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException(MAX_BODY_BYTES_PROPERTY + " must be positive");
        }
        if (analysisResultMaxBodyBytes <= 0) {
            throw new IllegalArgumentException(ANALYSIS_RESULT_MAX_BODY_BYTES_PROPERTY + " must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.analysisResultMaxBodyBytes = analysisResultMaxBodyBytes;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public int analysisResultMaxBodyBytes() {
        return analysisResultMaxBodyBytes;
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
        long limit = limitFor(request);
        byte[] body = readAtMost(request.getInputStream(), limit + 1L);
        if (body.length > limit) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(TOO_LARGE_BODY);
            return;
        }
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private long limitFor(HttpServletRequest request) {
        if (HttpMethod.POST.matches(request.getMethod())
                && ANALYSIS_RESULT_PATH.matcher(request.getRequestURI()).matches()) {
            return analysisResultMaxBodyBytes;
        }
        return maxBodyBytes;
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
