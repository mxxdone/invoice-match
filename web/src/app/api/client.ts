// Same-origin fetch adapter for the live read endpoints. Every call goes to the
// fixed local /backend proxy, which forwards only the Basic authorization
// header to the server-configured Core API. Nothing here stores or logs the
// credentials.

import type { CurrentUser, InvoiceCasePage } from './contract.ts';
import { buildInvoiceCaseQuery, type InvoiceCaseFilters } from './query.ts';
import {
  ApiRequestError,
  buildBasicAuthHeader,
  toApiRequestError,
  type Credentials,
} from './transport.ts';

const PROXY_PREFIX = '/backend';

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

async function requestJson<T>(
  path: string,
  credentials: Credentials,
  signal?: AbortSignal,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${PROXY_PREFIX}${path}`, {
      method: 'GET',
      headers: {
        authorization: buildBasicAuthHeader(credentials),
        accept: 'application/json',
      },
      cache: 'no-store',
      signal,
    });
  } catch (caught) {
    if (isAbortError(caught)) {
      throw caught;
    }
    throw new ApiRequestError(0, 'NETWORK_ERROR', '서버에 연결하지 못했습니다.');
  }
  if (!response.ok) {
    let body = '';
    try {
      body = await response.text();
    } catch (caught) {
      // Preserve cancellation: reading the error body can itself be aborted, and
      // swallowing it into a 401/403 would let a stale request sign the user out.
      if (isAbortError(caught)) {
        throw caught;
      }
    }
    throw toApiRequestError(response.status, body);
  }
  return (await response.json()) as T;
}

export function fetchCurrentUser(
  credentials: Credentials,
  signal?: AbortSignal,
): Promise<CurrentUser> {
  return requestJson<CurrentUser>('/api/me', credentials, signal);
}

export function fetchInvoiceCases(
  credentials: Credentials,
  filters: InvoiceCaseFilters,
  signal?: AbortSignal,
): Promise<InvoiceCasePage> {
  const query = new URLSearchParams(buildInvoiceCaseQuery(filters)).toString();
  return requestJson<InvoiceCasePage>(
    `/api/invoice-cases${query ? `?${query}` : ''}`,
    credentials,
    signal,
  );
}
