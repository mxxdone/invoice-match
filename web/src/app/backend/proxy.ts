import { CORE_API_TIMEOUT_MS, coreApiUrl, resolveBackendTarget } from './target.ts';

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
};

function errorResponse(status: number, code: string, message: string): Response {
  return Response.json(
    { code, message },
    { status, headers: { 'cache-control': 'no-store' } },
  );
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

  const headers: Record<string, string> = { accept: 'application/json' };
  const authorization = request.headers.get('authorization');
  if (authorization) {
    headers.authorization = authorization;
  }

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
