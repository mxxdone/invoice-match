import type { NextRequest } from 'next/server';
import { proxyRead, proxyWrite } from '../proxy.ts';

// Same-origin proxy used only to bypass CORS for HTTP Basic calls to the Core
// API. The target host is fixed by CORE_API_URL and the path (plus method for
// writes) is allowlisted; redirects are never followed and the upstream call has
// a finite deadline that is also released when the browser cancels. Write
// request bodies are stream-bounded to the Core API limit.
export const dynamic = 'force-dynamic';

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  return proxyRead(request, path, request.nextUrl.search, { env: process.env.CORE_API_URL });
}

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  return proxyWrite(request, 'POST', path, { env: process.env.CORE_API_URL });
}

export async function PUT(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  return proxyWrite(request, 'PUT', path, { env: process.env.CORE_API_URL });
}
