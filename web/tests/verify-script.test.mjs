import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  VerifyError,
  validatePorts,
  assertPortsFree,
  dockerRun,
  randomContainerName,
  waitForHttp,
  runVerification,
} from '../../scripts/lib/verify-core.mjs';
import {
  buildConfig,
  createPortProbe,
  createRequest,
  loopbackUrl,
  startChild,
} from '../../scripts/lib/adapters.mjs';
import { runChecks } from '../../scripts/lib/checks.mjs';

async function poll(predicate, timeoutMs = 4000, intervalMs = 50) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await predicate()) return true;
    if (Date.now() >= deadline) return false;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

function config() {
  return {
    ports: { pg: 55440, mock: 18082, core: 18080, web: 13100 },
    images: { postgres: 'postgres:18-alpine' },
    pgEnv: { POSTGRES_DB: 'invoice_match', POSTGRES_USER: 'invoice_match', POSTGRES_PASSWORD: 'throwaway' },
    pgPublish: '127.0.0.1:55440:5432',
    pgReadyArgs: ['pg_isready', '-U', 'invoice_match', '-d', 'invoice_match'],
    readinessTimeoutMs: 100,
    children: [
      { name: 'mock', cmd: 'node', args: [], env: {}, readiness: { url: 'http://127.0.0.1:18082/health', verify: (b) => b.includes('UP') } },
      { name: 'core', cmd: 'java', args: [], env: {}, readiness: { url: 'http://127.0.0.1:18080/actuator/health', verify: (b) => b.includes('UP') } },
      { name: 'web', cmd: 'node', args: [], env: {}, readiness: { url: 'http://127.0.0.1:13100/login' } },
    ],
  };
}

function harness(overrides = {}) {
  const state = {
    started: [],
    childStops: [],
    stops: [],
    dockerRuns: [],
    requests: [],
    verifyCalls: 0,
    now: 0,
    alive: { mock: true, core: true, web: true },
    ...overrides,
  };
  const deps = {
    config: state.config ?? config(),
    isPortOpen: async () => state.portOpen === true,
    docker: {
      run: async (args) => { state.dockerRuns.push(args); return state.dockerRunResult ?? { code: 0, stdout: 'cid-1\n', stderr: '' }; },
      exec: async () => ({ code: 0, stdout: '', stderr: '' }),
      stop: async (id) => { state.stops.push(id); return state.stopResult ?? { code: 0 }; },
    },
    startChild: async (spec) => {
      const child = {
        name: spec.name,
        killed: false,
        kill() { this.killed = true; },
        alive() { return state.alive[spec.name] !== false; },
        async stop() { state.childStops.push(spec.name); return state.childStopResult ?? { ok: true }; },
      };
      state.started.push(child);
      return child;
    },
    request: async (url, init) => {
      state.requests.push({ url, method: init?.method ?? 'GET' });
      return state.requestResult ? state.requestResult(url) : { ok: true, status: 200, body: 'UP' };
    },
    now: () => state.now,
    sleep: async (ms) => { state.now += ms; },
    log: () => {},
    verify: async () => { state.verifyCalls += 1; return {}; },
  };
  return { state, deps };
}

test('port preflight rejects duplicates, range errors and occupied ports', async () => {
  assert.throws(() => validatePorts({ a: 8080, b: 8080 }), /Duplicate port/);
  assert.throws(() => validatePorts({ a: 0 }), /Invalid port/);
  assert.throws(() => validatePorts({ a: 70000 }), /Invalid port/);
  await assert.rejects(assertPortsFree({ a: 8080 }, async () => true), /already in use/);
});

test('an occupied port aborts before docker run', async () => {
  const { state, deps } = harness({ portOpen: true });
  await assert.rejects(runVerification(deps), /already in use/);
  assert.equal(state.dockerRuns.length, 0);
  assert.equal(state.stops.length, 0);
});

test('docker run exit 125 aborts and does not stop anything', async () => {
  const { state, deps } = harness({ dockerRunResult: { code: 125, stdout: '', stderr: 'port is already allocated' } });
  await assert.rejects(runVerification(deps), /docker run failed/);
  assert.equal(state.stops.length, 0);
  assert.equal(state.started.length, 0);
});

test('docker run publishes on IPv4 loopback and requires a container id', async () => {
  const runs = [];
  await assert.rejects(
    dockerRun({ docker: { run: async (args) => { runs.push(args); return { code: 0, stdout: '\n', stderr: '' }; } }, name: 'x', image: 'i', env: {}, publish: '127.0.0.1:1:5432' }),
    /no container id/,
  );
  assert.ok(runs[0].includes('127.0.0.1:1:5432'));
  const run = await dockerRun({ docker: { run: async () => ({ code: 0, stdout: 'abc123\n', stderr: '' }) }, name: 'x', image: 'i', env: {}, publish: '127.0.0.1:1:5432' });
  assert.equal(run.id, 'abc123');
});

test('a random container name is unique', () => {
  assert.notEqual(randomContainerName('im-verify-pg'), randomContainerName('im-verify-pg'));
});

test('a child that exits during readiness stops the run before any seed request', async () => {
  const { state, deps } = harness({ alive: { mock: false, core: true, web: true } });
  await assert.rejects(runVerification(deps), /Child exited/);
  assert.equal(state.verifyCalls, 0);
  assert.equal(state.requests.filter((r) => r.method !== 'GET').length, 0);
  assert.deepEqual(state.stops, ['cid-1']);
  assert.deepEqual(state.childStops, ['mock']);
});

test('persistent non-2xx readiness times out before any seed request', async () => {
  const { state, deps } = harness({ requestResult: () => ({ ok: false, status: 503, body: 'down' }) });
  await assert.rejects(runVerification(deps), /Readiness timeout/);
  assert.equal(state.verifyCalls, 0);
  assert.equal(state.requests.filter((r) => r.method !== 'GET').length, 0);
  assert.deepEqual(state.stops, ['cid-1']);
});

test('a successful readiness runs verification and stops every created child and container', async () => {
  const { state, deps } = harness();
  const result = await runVerification(deps);
  assert.equal(result.ok, true);
  assert.equal(state.verifyCalls, 1);
  assert.deepEqual(state.childStops, ['mock', 'core', 'web']);
  assert.deepEqual(state.stops, ['cid-1']);
});

test('a docker stop failure is reported and never claims success', async () => {
  const { deps } = harness({ stopResult: { code: 1 } });
  const result = await runVerification(deps);
  assert.equal(result.ok, false);
  assert.match(result.cleanupErrors.join(' '), /docker stop cid-1/);
});

test('a child stop failure is reported', async () => {
  const { state, deps } = harness({ childStopResult: { ok: false, error: 'did not exit' } });
  const result = await runVerification(deps);
  assert.equal(result.ok, false);
  assert.equal(state.childStops.length, 3);
  assert.match(result.cleanupErrors.join(' '), /did not exit/);
});

test('waitForHttp checks 2xx and the verify predicate', async () => {
  const ok = await waitForHttp({ request: async () => ({ ok: true, status: 200, body: '{"status":"UP"}' }), url: 'u', isChildAlive: () => true, verify: (b) => b.includes('UP'), timeoutMs: 50, now: () => 0, sleep: async () => {} });
  assert.equal(ok.status, 200);
  let clock = 0;
  await assert.rejects(
    waitForHttp({ request: async () => ({ ok: true, status: 200, body: 'starting' }), url: 'u', isChildAlive: () => true, verify: (b) => b.includes('UP'), timeoutMs: 50, intervalMs: 50, now: () => clock, sleep: async () => { clock += 50; } }),
    VerifyError,
  );
});

test('the script never mutates the caller process environment', async () => {
  const original = process.env.DB_PASSWORD;
  process.env.DB_PASSWORD = 'preserve-me';
  const { deps } = harness();
  await runVerification(deps);
  assert.equal(process.env.DB_PASSWORD, 'preserve-me');
  if (original === undefined) delete process.env.DB_PASSWORD;
  else process.env.DB_PASSWORD = original;
});

// --- production adapter contract (real spawn) ---

test('startChild survives a missing executable without an unhandled error', async () => {
  const child = startChild({ name: 'missing', cmd: 'definitely-not-a-real-exe-xyz-123', args: [], cwd: process.cwd(), env: {} });
  assert.equal(await poll(() => child.alive() === false), true);
  const stop = await child.stop();
  assert.equal(stop.ok, true);
});

test('startChild survives an invalid cwd without an unhandled error', async () => {
  const child = startChild({ name: 'badcwd', cmd: process.execPath, args: ['-e', ''], cwd: join(tmpdir(), 'definitely-missing-dir-xyz-123'), env: {} });
  assert.equal(await poll(() => child.alive() === false), true);
  const stop = await child.stop();
  assert.equal(stop.ok, true);
});

test('a real dummy child is terminated within the bound', async () => {
  const child = startChild({ name: 'dummy', cmd: process.execPath, args: ['-e', 'setInterval(() => {}, 1000)'], cwd: process.cwd(), env: {} });
  assert.equal(await poll(() => child.alive() === true), true);
  const stop = await child.stop();
  assert.equal(stop.ok, true);
  assert.equal(child.alive(), false);
});

test('fixed IPv4 loopback ignores an unrelated IPv6-only listener', async (t) => {
  const freePort = await new Promise((resolve, reject) => {
    const probe = createServer();
    probe.once('error', reject);
    probe.listen(0, '127.0.0.1', () => { const { port } = probe.address(); probe.close(() => resolve(port)); });
  });

  let ipv6Requests = 0;
  const ipv6 = http.createServer((_req, res) => { ipv6Requests += 1; res.end('ipv6'); });
  try {
    await new Promise((resolve, reject) => {
      ipv6.once('error', reject);
      ipv6.listen(freePort, '::1', resolve);
    });
  } catch {
    t.skip('IPv6 loopback is not available on this host');
    return;
  }

  const child = startChild({
    name: 'ipv4',
    cmd: process.execPath,
    args: ['-e', `require('http').createServer((q,s)=>s.end('ipv4')).listen(${freePort},'127.0.0.1')`],
    cwd: process.cwd(),
    env: {},
  });
  try {
    // IPv4 loopback is genuinely free even though ::1 is occupied.
    assert.equal(await createPortProbe()(freePort), false);
    const request = createRequest({ timeoutMs: 1000 });
    const ready = await poll(async () => {
      try { return (await request(`${loopbackUrl(freePort)}/`)).status === 200; } catch { return false; }
    }, 5000, 100);
    assert.equal(ready, true);
    const response = await request(`${loopbackUrl(freePort)}/`);
    assert.equal(response.body, 'ipv4');
    assert.equal(ipv6Requests, 0, 'the IPv6-only fixture must receive no writes');
  } finally {
    await child.stop();
    await new Promise((resolve) => ipv6.close(resolve));
  }
});

test('buildConfig uses 127.0.0.1 everywhere and no localhost', () => {
  const cfg = buildConfig({ ports: { pg: 1, mock: 2, core: 3, web: 4 }, repo: 'C:/repo', pgPassword: 'p' });
  const text = JSON.stringify(cfg);
  assert.equal(text.includes('localhost'), false);
  assert.ok(cfg.pgPublish.startsWith('127.0.0.1:'));
  const core = cfg.children.find((c) => c.name === 'core-api');
  assert.ok(core.args.includes('--server.address=127.0.0.1'));
  assert.ok(core.env.DB_URL.includes('127.0.0.1'));
  assert.ok(core.env.PURCHASING_BASE_URL.startsWith('http://127.0.0.1:'));
  const web = cfg.children.find((c) => c.name === 'web');
  assert.equal(web.env.HOSTNAME, '127.0.0.1');
  assert.ok(web.env.CORE_API_URL.startsWith('http://127.0.0.1:'));
});

test('runChecks asserts seed statuses and stops on a submit failure with a sanitized error', async () => {
  const cfg = { ports: { core: 3, web: 4 } };
  const calls = [];
  const base = {
    '/api/me': () => ({ ok: true, status: 200, body: JSON.stringify({ username: 'approver', roles: ['APPROVER'] }) }),
    '/actuator/health': () => ({ ok: true, status: 200, body: '{"status":"UP"}' }),
  };
  const request = async (url, init = {}) => {
    calls.push(init.method ?? 'GET');
    if (url.endsWith('/api/me') && (init.method ?? 'GET') === 'GET' && !init.headers?.authorization) return { ok: false, status: 401, body: '{}' };
    if (url.endsWith('/api/invoice-cases') && init.method === 'POST') return { ok: true, status: 201, body: JSON.stringify({ id: 'c1', version: 1 }) };
    if (url.includes('/draft') && init.method === 'PUT') return { ok: true, status: 200, body: JSON.stringify({ version: 2 }) };
    if (url.includes('/submit') && init.method === 'POST') return { ok: false, status: 500, body: JSON.stringify({ code: 'BOOM' }) };
    for (const [suffix, response] of Object.entries(base)) {
      if (url.endsWith(suffix)) return response();
    }
    return { ok: true, status: 200, body: '{}' };
  };
  await assert.rejects(
    runChecks({ request, config: cfg, guard: () => {}, log: () => {} }),
    (error) => {
      assert.equal(error instanceof VerifyError, true);
      assert.match(error.message, /submit did not return 200/);
      assert.equal(/password|Basic|authorization/i.test(error.message), false);
      return true;
    },
  );
  assert.ok(calls.includes('POST'));
  assert.ok(calls.includes('PUT'));
});
