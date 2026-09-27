package com.invoicematch.core.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Request wrapper that serves a bounded, already-read body to downstream code.
 * It makes the enforced size limit independent of {@code Content-Length}: the
 * bytes were read from the actual stream, so a chunked request without a length
 * is bounded too.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final Charset charset;

    public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
        String encoding = request.getCharacterEncoding();
        this.charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
    }

    public byte[] body() {
        return body;
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream delegate = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return delegate.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return delegate.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return delegate.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async reads are not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }
}
