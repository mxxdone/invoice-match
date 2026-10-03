package com.invoicematch.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Boundary tests for the request body policy. The limit is enforced against the
 * read stream, so it holds for a declared Content-Length and for a chunked
 * request that declares none.
 */
class BoundedRequestBodyFilterTest {

    private static final int MAX = 16;
    private static final int ANALYSIS_RESULT_MAX = 64;

    private final BoundedRequestBodyFilter filter = new BoundedRequestBodyFilter(MAX, ANALYSIS_RESULT_MAX);

    @Test
    void allowsABodyExactlyAtTheLimit() throws Exception {
        MockHttpServletRequest request = postWithBody("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, chain(reached));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsABodyOneByteOverTheLimit() throws Exception {
        MockHttpServletRequest request = postWithBody("0123456789abcdefg".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, chain(reached));

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void rejectsAChunkedBodyWithNoContentLength() throws Exception {
        MockHttpServletRequest base = postWithBody("x".repeat(MAX + 5).getBytes(StandardCharsets.UTF_8));
        HttpServletRequest chunked = new HttpServletRequestWrapper(base) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public String getHeader(String name) {
                return "Transfer-Encoding".equalsIgnoreCase(name) ? "chunked" : super.getHeader(name);
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(chunked, response, chain(reached));

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void rejectsAnInvalidLimitConfiguration() {
        assertThatThrownBy(() -> new BoundedRequestBodyFilter(0, ANALYSIS_RESULT_MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedRequestBodyFilter(MAX, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsTheLargerResultBodyOnlyOnTheExactResultPath() throws Exception {
        byte[] body = "x".repeat(MAX + 5).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest resultPath = new MockHttpServletRequest(
                "POST", "/internal/analysis-runs/00000000-0000-0000-0000-0000000000aa/results");
        resultPath.setContentType("application/json");
        resultPath.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(resultPath, response, chain(reached));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void keepsTheDefaultCapForTheClaimAndHeartbeatPaths() throws Exception {
        for (String path : new String[] {
                "/internal/analysis-runs/00000000-0000-0000-0000-0000000000aa/claim",
                "/internal/analysis-runs/00000000-0000-0000-0000-0000000000aa/heartbeat"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setContentType("application/json");
            request.setContent("x".repeat(MAX + 5).getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();

            filter.doFilter(request, response, chain(reached));

            assertThat(reached).as(path).isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(413);
        }
    }

    @Test
    void resultStreamCapRejectsDeclaredAndChunkedBodiesWithoutCallingTheChain() throws Exception {
        for (boolean chunked : new boolean[] {false, true}) {
            MockHttpServletRequest base = new MockHttpServletRequest("POST",
                    "/internal/analysis-runs/00000000-0000-0000-0000-0000000000aa/results");
            base.setContent("x".repeat(ANALYSIS_RESULT_MAX + 1).getBytes(StandardCharsets.UTF_8));
            HttpServletRequest request = chunked ? new HttpServletRequestWrapper(base) {
                @Override public int getContentLength() { return -1; }
                @Override public long getContentLengthLong() { return -1; }
                @Override public String getHeader(String name) {
                    return "Transfer-Encoding".equalsIgnoreCase(name) ? "chunked" : super.getHeader(name);
                }
            } : base;
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, response, chain(reached));
            assertThat(reached).isFalse();
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    private static MockHttpServletRequest postWithBody(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/invoice-cases");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    private static FilterChain chain(AtomicBoolean reached) {
        return (request, response) -> reached.set(true);
    }
}
