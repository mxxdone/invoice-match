import type { NextRequest } from 'next/server';
import { proxyRead } from '../proxy.ts';

// Same-origin, read-only proxy used only to bypass CORS for HTTP Basic calls to
// the Core API. The target host is fixed by CORE_API_URL and the path is
// allowlisted; redirects are never followed and the upstream call has a finite
// deadline that is also released when the browser cancels.
export const dynamic = 'force-dynamic';

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  return proxyRead(request, path, request.nextUrl.search, { env: process.env.CORE_API_URL });
}
