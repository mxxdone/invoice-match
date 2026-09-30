import type { NextRequest } from 'next/server';
import { CORE_API_TIMEOUT_MS, coreApiUrl, resolveBackendTarget } from '../target';

// Read-only, same-origin proxy used only to bypass CORS for HTTP Basic calls to
// the Core API. The target host is fixed by CORE_API_URL and the path is
// allowlisted in `resolveBackendTarget`; no user input selects the target.
// Redirects are never followed, so the Basic header cannot leak to the
// redirect target, and the upstream call is bounded by a finite deadline that
// is also released when the browser cancels the request.
export const dynamic = 'force-dynamic';

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  const target = resolveBackendTarget(
    coreApiUrl(process.env.CORE_API_URL),
    path,
    request.nextUrl.search,
  );
  if (!target) {
    return Response.json(
      { code: 'NOT_FOUND', message: 'Unsupported backend read endpoint' },
      { status: 404 },
    );
  }

  const headers: Record<string, string> = { accept: 'application/json' };
  const authorization = request.headers.get('authorization');
  if (authorization) {
    headers.authorization = authorization;
  }

  const timeout = AbortSignal.timeout(CORE_API_TIMEOUT_MS);
  const signal = AbortSignal.any([request.signal, timeout]);

  let upstream: Response;
  try {
    upstream = await fetch(target, { method: 'GET', headers, cache: 'no-store', redirect: 'manual', signal });
  } catch {
    if (request.signal.aborted) {
      return Response.json({ code: 'CLIENT_ABORTED', message: 'Request was cancelled' }, { status: 503 });
    }
    if (timeout.aborted) {
      return Response.json({ code: 'CORE_API_TIMEOUT', message: 'Core API timed out' }, { status: 504 });
    }
    return Response.json({ code: 'CORE_API_UNAVAILABLE', message: 'Core API is unreachable' }, { status: 503 });
  }

  if (upstream.status >= 300 && upstream.status < 400) {
    return Response.json(
      { code: 'CORE_API_REDIRECT', message: 'Core API redirect is not followed' },
      { status: 502 },
    );
  }

  const body = await upstream.text();
  return new Response(body, {
    status: upstream.status,
    headers: {
      'content-type': upstream.headers.get('content-type') ?? 'application/json',
      'cache-control': 'no-store',
    },
  });
}
