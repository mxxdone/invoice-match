// Fixed-target resolution for the same-origin Core API proxy. The host comes
// only from the server-side CORE_API_URL environment variable and the path is a
// small, pattern-validated allowlist, so the route can never be turned into an
// open proxy or used to reach a write endpoint.

export const DEFAULT_CORE_API_URL = 'http://localhost:8080';

// Bounded upstream deadline so a hung Core API cannot hold the proxy request
// open forever. A redirect is never followed (the request carries the Basic
// header and must not leak it to another origin).
export const CORE_API_TIMEOUT_MS = 10000;

// Path segments that select a live case read endpoint are validated rather than
// matched literally: a case id must be a canonical UUID and a bundle version or
// snapshot number a small positive integer. In a route pattern '*' is one UUID
// and '#' is one positive integer; every other token must match the literal.
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const POSITIVE_INT_PATTERN = /^[1-9][0-9]{0,8}$/;

const ALLOWED_ROUTES: readonly (readonly string[])[] = [
  ['api', 'me'],
  ['api', 'invoice-cases'],
  ['api', 'invoice-cases', '*'],
  ['api', 'invoice-cases', '*', 'evidence-bundles'],
  ['api', 'invoice-cases', '*', 'evidence-bundles', '#'],
  ['api', 'invoice-cases', '*', 'match'],
  ['api', 'invoice-cases', '*', 'review-snapshots', 'latest'],
  ['api', 'invoice-cases', '*', 'review-snapshots', '#', 'freshness'],
  ['api', 'invoice-cases', '*', 'review-decisions'],
  ['api', 'invoice-cases', '*', 'audit-entries'],
  ['api', 'invoice-cases', '*', 'handoff'],
];

// Write endpoints are allowed only with their exact HTTP method. The proxy
// forwards the authenticated principal as-is; the server remains the authority
// for role and ownership, so this list gates reachability, not authorization.
export type BackendMutationMethod = 'POST' | 'PUT';

const MUTATION_ROUTES: readonly (readonly [BackendMutationMethod, readonly string[]])[] = [
  ['POST', ['api', 'invoice-cases']],
  ['PUT', ['api', 'invoice-cases', '*', 'draft']],
  ['POST', ['api', 'invoice-cases', '*', 'submit']],
  ['POST', ['api', 'invoice-cases', '*', 'revisions']],
  ['POST', ['api', 'invoice-cases', '*', 'match']],
  ['POST', ['api', 'invoice-cases', '*', 'review-snapshots']],
  ['POST', ['api', 'invoice-cases', '*', 'mapping-decisions']],
  ['POST', ['api', 'invoice-cases', '*', 'supplement-requests']],
  ['POST', ['api', 'invoice-cases', '*', 'reject']],
  ['POST', ['api', 'invoice-cases', '*', 'approve']],
];

function matchesRoute(segments: readonly string[], pattern: readonly string[]): boolean {
  if (segments.length !== pattern.length) return false;
  for (let index = 0; index < pattern.length; index += 1) {
    const token = pattern[index];
    const value = segments[index];
    if (token === '*') {
      if (!UUID_PATTERN.test(value)) return false;
    } else if (token === '#') {
      if (!POSITIVE_INT_PATTERN.test(value)) return false;
    } else if (value !== token) {
      return false;
    }
  }
  return true;
}

export function coreApiUrl(env: string | undefined): string {
  const value = env?.trim();
  return value ? value : DEFAULT_CORE_API_URL;
}

export function resolveBackendTarget(
  baseUrl: string,
  segments: string[],
  search: string,
): string | null {
  if (!ALLOWED_ROUTES.some((pattern) => matchesRoute(segments, pattern))) {
    return null;
  }
  const path = segments.join('/');
  const base = baseUrl.replace(/\/+$/, '');
  return `${base}/${path}${search}`;
}

// Request bodies are bounded to the same 256 KiB the Core API enforces, read
// from the actual stream so a chunked body with no Content-Length is bounded
// too. Response bodies keep the unbounded stream the read proxy already used.
export const REQUEST_BODY_LIMIT_BYTES = 262144;

export function resolveBackendMutationTarget(
  baseUrl: string,
  method: BackendMutationMethod,
  segments: string[],
): string | null {
  const allowed = MUTATION_ROUTES.some(
    ([routeMethod, pattern]) => routeMethod === method && matchesRoute(segments, pattern),
  );
  if (!allowed) {
    return null;
  }
  const base = baseUrl.replace(/\/+$/, '');
  return `${base}/${segments.join('/')}`;
}
