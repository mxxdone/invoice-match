import {
  CORE_API_TIMEOUT_MS,
  REQUEST_BODY_LIMIT_BYTES,
  coreApiUrl,
  resolveBackendMutationTarget,
  resolveBackendTarget,
  type BackendMutationMethod,
} from './target.ts';

// Core of the read-only /backend proxy. Kept framework-free so the body-error
// contract can be tested against real Response streams.
//
// The upstream call and the body consumption share one error boundary: a
// response whose headers arrive and then trickle past the deadline, a body
// cancelled with the request, or a mid-stream connection close must all map to
// the same stable JSON errors instead of escaping as a framework 500.

export type ProxyOptions = {
  env?: string | undefined;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
  requestBodyLimitBytes?: number;
};

function errorResponse(status: number, code: string, message: string): Response {
  return Response.json(
    { code, message },
    { status, headers: { 'cache-control': 'no-store' } },
  );
}

// Reads the inbound body from the actual stream so a chunked request with no
// Content-Length is bounded too. The read, the oversize cancel and the final
// unlock are all raced against the shared deadline/cancellation signal, so a
// client that opens a body and then stalls (never enqueues, never closes) cannot
// hold the proxy request open past the deadline. Returns null when the body
// exceeds the limit; the caller maps that to the same 413 the Core API returns.
async function readBoundedRequestBody(
  request: Request,
  limit: number,
  signal: AbortSignal,
): Promise<Uint8Array<ArrayBuffer> | null> {
  if (!request.body) {
    return new Uint8Array(new ArrayBuffer(0));
  }
  const reader = request.body.getReader();
  const aborted = new Promise<never>((_resolve, reject) => {
    const onAbort = () => reject(signal.reason ?? new DOMException('aborted', 'AbortError'));
    if (signal.aborted) {
      onAbort();
      return;
    }
    signal.addEventListener('abort', onAbort, { once: true });
  });
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      if (signal.aborted) throw signal.reason ?? new DOMException('aborted', 'AbortError');
      const { done, value } = await Promise.race([reader.read(), aborted]);
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > limit) {
        reader.cancel().catch(() => {});
        return null;
      }
      chunks.push(value);
    }
  } finally {
    // If we stopped on abort, a read is still attached; cancel it (bounded by
    // the same already-fired abort) before releasing so releaseLock cannot throw
    // on a live stream, and so a stalled reader never leaks.
    if (signal.aborted) {
      try {
        await Promise.race([reader.cancel().catch(() => {}), aborted.catch(() => {})]);
      } catch {
        // The abort already decided the outcome.
      }
    }
    try {
      reader.releaseLock();
    } catch {
      // A cancel in flight can still own the lock; nothing more to release.
    }
  }
  const body = new Uint8Array(new ArrayBuffer(total));
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

function buildForwardHeaders(request: Request): Record<string, string> {
  const headers: Record<string, string> = { accept: 'application/json' };
  const authorization = request.headers.get('authorization');
  if (authorization) {
    headers.authorization = authorization;
  }
  return headers;
}

export async function proxyRead(
  request: Request,
  segments: string[],
  search: string,
  options: ProxyOptions = {},
): Promise<Response> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const target = resolveBackendTarget(coreApiUrl(options.env), segments, search);
  if (!target) {
    return errorResponse(404, 'NOT_FOUND', 'Unsupported backend read endpoint');
  }

  const headers = buildForwardHeaders(request);
  const timeout = AbortSignal.timeout(options.timeoutMs ?? CORE_API_TIMEOUT_MS);
  const signal = AbortSignal.any([request.signal, timeout]);

  let upstream: Response;
  try {
    upstream = await fetchImpl(target, {
      method: 'GET',
      headers,
      cache: 'no-store',
      redirect: 'manual',
      signal,
    });
    if (upstream.status >= 300 && upstream.status < 400) {
      return errorResponse(502, 'CORE_API_REDIRECT', 'Core API redirect is not followed');
    }
    const body = await upstream.text();
    return new Response(body, {
      status: upstream.status,
      headers: {
        'content-type': upstream.headers.get('content-type') ?? 'application/json',
        'cache-control': 'no-store',
      },
    });
  } catch {
    if (request.signal.aborted) {
      return errorResponse(503, 'CLIENT_ABORTED', 'Request was cancelled');
    }
    if (timeout.aborted) {
      return errorResponse(504, 'CORE_API_TIMEOUT', 'Core API timed out');
    }
    return errorResponse(503, 'CORE_API_UNAVAILABLE', 'Core API is unreachable');
  }
}

// Same-origin write proxy. The method+path pair must match the mutation
// allowlist exactly, the inbound body is stream-bounded to the Core API limit,
// redirects are refused and the whole call shares one deadline and the client
// cancellation signal.
export async function proxyWrite(
  request: Request,
  method: BackendMutationMethod,
  segments: string[],
  options: ProxyOptions = {},
): Promise<Response> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const limit = options.requestBodyLimitBytes ?? REQUEST_BODY_LIMIT_BYTES;
  const target = resolveBackendMutationTarget(coreApiUrl(options.env), method, segments);
  if (!target) {
    return errorResponse(404, 'NOT_FOUND', 'Unsupported backend write endpoint');
  }

  const timeout = AbortSignal.timeout(options.timeoutMs ?? CORE_API_TIMEOUT_MS);
  const signal = AbortSignal.any([request.signal, timeout]);

  try {
    const body = await readBoundedRequestBody(request, limit, signal);
    if (body === null) {
      return errorResponse(413, 'PAYLOAD_TOO_LARGE', 'Request body exceeds the configured limit');
    }
    const headers = buildForwardHeaders(request);
    const contentType = request.headers.get('content-type');
    if (contentType) {
      headers['content-type'] = contentType;
    }

    const upstream = await fetchImpl(target, {
      method,
      headers,
      body: body.byteLength > 0 ? new Blob([body]) : undefined,
      cache: 'no-store',
      redirect: 'manual',
      signal,
    });
    if (upstream.status >= 300 && upstream.status < 400) {
      return errorResponse(502, 'CORE_API_REDIRECT', 'Core API redirect is not followed');
    }
    const responseBody = await upstream.text();
    return new Response(responseBody, {
      status: upstream.status,
      headers: {
        'content-type': upstream.headers.get('content-type') ?? 'application/json',
        'cache-control': 'no-store',
      },
    });
  } catch {
    if (request.signal.aborted) {
      return errorResponse(503, 'CLIENT_ABORTED', 'Request was cancelled');
    }
    if (timeout.aborted) {
      return errorResponse(504, 'CORE_API_TIMEOUT', 'Core API timed out');
    }
    return errorResponse(503, 'CORE_API_UNAVAILABLE', 'Core API is unreachable');
  }
}
