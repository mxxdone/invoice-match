import type { NextRequest } from 'next/server';
import { coreApiUrl, resolveBackendTarget } from '../target';

// Read-only, same-origin proxy used only to bypass CORS for HTTP Basic calls to
// the Core API. The target host is fixed by CORE_API_URL and the path is
// allowlisted in `resolveBackendTarget`; no user input selects the target.
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

  let upstream: Response;
  try {
    upstream = await fetch(target, { method: 'GET', headers, cache: 'no-store' });
  } catch {
    return Response.json(
      { code: 'CORE_API_UNAVAILABLE', message: 'Core API is unreachable' },
      { status: 503 },
    );
  }

  const body = await upstream.text();
  return new Response(body, {
    status: upstream.status,
    headers: {
      'content-type': upstream.headers.get('content-type') ?? 'application/json',
    },
  });
}
