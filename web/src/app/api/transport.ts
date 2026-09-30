// Transport helpers shared by the live read adapters. Credentials are only ever
// turned into a per-request Basic header; they are never persisted here.

export type Credentials = {
  username: string;
  password: string;
};

// Structured server fields that carry the reason a write was refused. They are
// kept verbatim so the UI can show the exact stale reasons / allocation
// shortfalls instead of flattening every 409 into a single message.
export type ApiErrorDetails = Record<string, unknown>;

export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: ApiErrorDetails | null;

  constructor(status: number, code: string, message: string, details: ApiErrorDetails | null = null) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

function encodeBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

export function buildBasicAuthHeader(credentials: Credentials): string {
  return `Basic ${encodeBase64(`${credentials.username}:${credentials.password}`)}`;
}

// Maps an HTTP status plus the shared `{code,message}` body to a typed error.
export function toApiRequestError(status: number, bodyText: string): ApiRequestError {
  let code = `HTTP_${status}`;
  let message = '요청을 처리하지 못했습니다.';
  if (status === 401) {
    message = '로그인이 필요합니다.';
  } else if (status === 403) {
    message = '이 작업을 수행할 권한이 없습니다.';
  } else if (status >= 500) {
    message = '서버와 통신하지 못했습니다.';
  }
  const trimmed = bodyText?.trim();
  let details: ApiErrorDetails | null = null;
  if (trimmed) {
    try {
      const parsed = JSON.parse(trimmed) as Record<string, unknown>;
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        details = parsed;
      }
      if (typeof parsed?.code === 'string' && parsed.code) {
        code = parsed.code;
      }
      if (typeof parsed?.message === 'string' && parsed.message) {
        message = parsed.message;
      }
    } catch {
      // Non-JSON body: keep the status-derived defaults.
    }
  }
  if (status === 401 && code === 'HTTP_401') {
    code = 'UNAUTHENTICATED';
  } else if (status === 403 && code === 'HTTP_403') {
    code = 'FORBIDDEN';
  }
  return new ApiRequestError(status, code, message, details);
}
