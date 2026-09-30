import test from 'node:test';
import assert from 'node:assert/strict';
import {
  VerifyError,
  validatePorts,
  assertPortsFree,
  dockerRun,
  randomContainerName,
  waitForHttp,
  runVerification,
} from '../../scripts/lib/verify-core.mjs';

function config() {
  return {
    ports: { pg: 55440, mock: 18082, core: 18080, web: 13100 },
    images: { postgres: 'postgres:18-alpine' },
    pgEnv: { POSTGRES_DB: 'invoice_match', POSTGRES_USER: 'invoice_match', POSTGRES_PASSWORD: 'throwaway' },
    pgReadyArgs: ['pg_isready', '-U', 'invoice_match', '-d', 'invoice_match'],
    readinessTimeoutMs: 100,
    children: [
      { name: 'mock', cmd: 'node', args: [], env: {}, readiness: { url: 'http://mock/health', verify: (b) => b.includes('UP') } },
      { name: 'core', cmd: 'java', args: [], env: {}, readiness: { url: 'http://core/actuator/health', verify: (b) => b.includes('UP') } },
      { name: 'web', cmd: 'node', args: [], env: {}, readiness: { url: 'http://web/login' } },
    ],
  };
}

function harness(overrides = {}) {
  const state = {
    started: [],
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
      stop: async (id) => { state.stops.push(id); },
    },
    startChild: (spec) => {
      const child = {
        name: spec.name,
        killed: false,
        kill() { this.killed = true; },
        alive() { return state.alive[spec.name] !== false; },
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

test('a container id is required and the created id is captured', async () => {
  await assert.rejects(
    dockerRun({ docker: { run: async () => ({ code: 0, stdout: '\n', stderr: '' }) }, name: 'x', image: 'i', env: {}, hostPort: 1 }),
    /no container id/,
  );
  const run = await dockerRun({ docker: { run: async () => ({ code: 0, stdout: 'abc123\n', stderr: '' }) }, name: 'x', image: 'i', env: {}, hostPort: 1 });
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
  assert.equal(state.started[0].killed, true);
});

test('persistent non-2xx readiness times out before any seed request', async () => {
  const { state, deps } = harness({ requestResult: () => ({ ok: false, status: 503, body: 'down' }) });
  await assert.rejects(runVerification(deps), /Readiness timeout/);
  assert.equal(state.verifyCalls, 0);
  assert.equal(state.requests.filter((r) => r.method !== 'GET').length, 0);
  assert.deepEqual(state.stops, ['cid-1']);
});

test('a successful readiness runs verification and cleans up by id', async () => {
  const { state, deps } = harness();
  const result = await runVerification(deps);
  assert.equal(result.ok, true);
  assert.equal(state.verifyCalls, 1);
  assert.equal(state.started.length, 3);
  assert.ok(state.started.every((child) => child.killed));
  assert.deepEqual(state.stops, ['cid-1']);
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
