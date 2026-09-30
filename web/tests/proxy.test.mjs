import test from 'node:test';
import assert from 'node:assert/strict';
import { proxyRead, proxyWrite } from '../src/app/backend/proxy.ts';

const encoder = new TextEncoder();

function request(signal) {
  return new Request('http://localhost/backend/api/me', {
    headers: { authorization: 'Basic dGVzdDp0ZXN0' },
    signal,
  });
}

async function expectJson(res, status, code) {
  assert.equal(res.status, status);
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal((await res.json()).code, code);
}

// A never-ending upstream body that errors when the passed fetch signal aborts,
// the way a real response body is aborted by its request signal.
function abortableBody(signal) {
  return new ReadableStream({
    start(controller) {
      signal.addEventListener(
        'abort',
        () => controller.error(signal.reason ?? new DOMException('aborted', 'AbortError')),
        { once: true },
      );
    },
  });
}

test('an unsupported path is 404 and every error response is no-store JSON', async () => {
  const res = await proxyRead(request(), ['api', 'invoice-cases', 'abc'], '', {
    env: 'http://core:8080',
    fetchImpl: async () => { throw new Error('must not fetch'); },
  });
  await expectJson(res, 404, 'NOT_FOUND');
});

test('a successful body is forwarded with no-store', async () => {
  const res = await proxyRead(request(), ['api', 'me'], '', {
    env: 'http://core:8080',
    fetchImpl: async () => new Response('{"username":"a"}', {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal(await res.text(), '{"username":"a"}');
});

test('a redirect is refused with 502 instead of following the Authorization header', async () => {
  const res = await proxyRead(request(), ['api', 'me'], '', {
    env: 'http://core:8080',
    fetchImpl: async () => new Response(null, { status: 302, headers: { location: 'http://evil.example' } }),
  });
  await expectJson(res, 502, 'CORE_API_REDIRECT');
});

test('a fetch rejection maps to 503 without leaking the target', async () => {
  const res = await proxyRead(request(), ['api', 'me'], '', {
    env: 'http://core:8080',
    fetchImpl: async () => { throw new TypeError('connect ECONNREFUSED 10.0.0.1:8080'); },
  });
  await expectJson(res, 503, 'CORE_API_UNAVAILABLE');
});

test('a body that errors mid-stream stays inside the error boundary', async () => {
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode('{"item'));
      setTimeout(() => controller.error(new TypeError('socket hang up')), 5);
    },
  });
  const res = await proxyRead(request(), ['api', 'me'], '', {
    env: 'http://core:8080',
    fetchImpl: async () => new Response(stream, { status: 200, headers: { 'content-type': 'application/json' } }),
  });
  await expectJson(res, 503, 'CORE_API_UNAVAILABLE');
});

test('headers then a trickling body times out as 504', async () => {
  const res = await proxyRead(request(), ['api', 'me'], '', {
    env: 'http://core:8080',
    timeoutMs: 30,
    fetchImpl: async (_url, init) => new Response(abortableBody(init.signal), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  });
  await expectJson(res, 504, 'CORE_API_TIMEOUT');
});

test('a client cancellation during the body maps to 503, not a framework error', async () => {
  const controller = new AbortController();
  const pending = proxyRead(request(controller.signal), ['api', 'me'], '', {
    env: 'http://core:8080',
    timeoutMs: 5000,
    fetchImpl: async (_url, init) => new Response(abortableBody(init.signal), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  });
  setTimeout(() => controller.abort(), 20);
  await expectJson(await pending, 503, 'CLIENT_ABORTED');
});

const CASE = '11111111-2222-3333-4444-555555555555';

function writeRequest(path, { body = '{"requestId":"r1"}', method = 'POST', signal } = {}) {
  return new Request(`http://localhost/backend/${path}`, {
    method,
    headers: { authorization: 'Basic dGVzdDp0ZXN0', 'content-type': 'application/json' },
    body,
    signal,
  });
}

test('a write path that is not allowlisted for the method is 404 and never fetched', async () => {
  const res = await proxyWrite(writeRequest(`api/invoice-cases/${CASE}/approve`, { method: 'PUT' }), 'PUT', ['api', 'invoice-cases', CASE, 'approve'], {
    env: 'http://core:8080',
    fetchImpl: async () => { throw new Error('must not fetch'); },
  });
  await expectJson(res, 404, 'NOT_FOUND');
});

test('a create write forwards the method and streamed body with no-store', async () => {
  let seen;
  const res = await proxyWrite(writeRequest('api/invoice-cases'), 'POST', ['api', 'invoice-cases'], {
    env: 'http://core:8080',
    fetchImpl: async (url, init) => {
      seen = { url, method: init.method, body: init.body, contentType: init.headers['content-type'] };
      return new Response('{"id":"x"}', { status: 201, headers: { 'content-type': 'application/json' } });
    },
  });
  assert.equal(res.status, 201);
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal(await res.text(), '{"id":"x"}');
  assert.equal(seen.url, 'http://core:8080/api/invoice-cases');
  assert.equal(seen.method, 'POST');
  assert.equal(seen.contentType, 'application/json');
  const sent = seen.body instanceof Blob ? new Uint8Array(await seen.body.arrayBuffer()) : seen.body;
  assert.deepEqual(Array.from(sent), Array.from(new TextEncoder().encode('{"requestId":"r1"}')));
});

test('an oversized write body is 413 PAYLOAD_TOO_LARGE before any upstream call', async () => {
  const res = await proxyWrite(writeRequest('api/invoice-cases', { body: 'x'.repeat(20) }), 'POST', ['api', 'invoice-cases'], {
    env: 'http://core:8080',
    requestBodyLimitBytes: 10,
    fetchImpl: async () => { throw new Error('must not fetch'); },
  });
  await expectJson(res, 413, 'PAYLOAD_TOO_LARGE');
});

test('a write redirect is refused with 502 instead of leaking the Authorization header', async () => {
  const res = await proxyWrite(writeRequest('api/invoice-cases'), 'POST', ['api', 'invoice-cases'], {
    env: 'http://core:8080',
    fetchImpl: async () => new Response(null, { status: 302, headers: { location: 'http://evil.example' } }),
  });
  await expectJson(res, 502, 'CORE_API_REDIRECT');
});

test('a write fetch rejection maps to 503 without leaking the target', async () => {
  const res = await proxyWrite(writeRequest('api/invoice-cases'), 'POST', ['api', 'invoice-cases'], {
    env: 'http://core:8080',
    fetchImpl: async () => { throw new TypeError('connect ECONNREFUSED 10.0.0.1:8080'); },
  });
  await expectJson(res, 503, 'CORE_API_UNAVAILABLE');
});

test('a client cancellation during a write maps to 503 CLIENT_ABORTED', async () => {
  const controller = new AbortController();
  const pending = proxyWrite(writeRequest('api/invoice-cases', { signal: controller.signal }), 'POST', ['api', 'invoice-cases'], {
    env: 'http://core:8080',
    timeoutMs: 5000,
    fetchImpl: async (_url, init) => new Response(abortableBody(init.signal), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  });
  setTimeout(() => controller.abort(), 20);
  await expectJson(await pending, 503, 'CLIENT_ABORTED');
});

test('the read proxy still refuses write-only paths', async () => {
  const readOnlyRequest = new Request(`http://localhost/backend/api/invoice-cases/${CASE}/approve`, {
    headers: { authorization: 'Basic dGVzdDp0ZXN0' },
  });
  const res = await proxyRead(readOnlyRequest, ['api', 'invoice-cases', CASE, 'approve'], '', {
    env: 'http://core:8080',
    fetchImpl: async () => { throw new Error('must not fetch'); },
  });
  await expectJson(res, 404, 'NOT_FOUND');
});
