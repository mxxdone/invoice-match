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

    private final BoundedRequestBodyFilter filter = new BoundedRequestBodyFilter(MAX);

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
        assertThatThrownBy(() -> new BoundedRequestBodyFilter(0))
                .isInstanceOf(IllegalArgumentException.class);
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
