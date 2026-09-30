// Fixed-target resolution for the same-origin Core API proxy. The host comes
// only from the server-side CORE_API_URL environment variable and the path is a
// small allowlist, so the route can never be turned into an open proxy.

export const DEFAULT_CORE_API_URL = 'http://localhost:8080';

const ALLOWED_PATHS = new Set(['api/me', 'api/invoice-cases']);

export function coreApiUrl(env: string | undefined): string {
  const value = env?.trim();
  return value ? value : DEFAULT_CORE_API_URL;
}

export function resolveBackendTarget(
  baseUrl: string,
  segments: string[],
  search: string,
): string | null {
  const path = segments.join('/');
  if (!ALLOWED_PATHS.has(path)) {
    return null;
  }
  const base = baseUrl.replace(/\/+$/, '');
  return `${base}/${path}${search}`;
}
