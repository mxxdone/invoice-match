import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import {
  ComposeSmokeError,
  createRunStreaming,
  decideOutcome,
  sanitizeComposeEnv,
  validateDistinctPorts,
  buildOverrideYaml,
  assertConfigPublishesOnLoopback,
  runComposeSmoke,
} from '../../scripts/lib/compose-core.mjs';

function composeHarness(overrides = {}) {
  const state = { composeCalls: [], written: [], removed: [], verifyCalls: 0 };
  const deps = {
    project: overrides.project ?? 'im-p111-test',
    envPath: overrides.envPath ?? 'C:/tmp/env',
    overridePath: overrides.overridePath ?? 'C:/tmp/override',
    generatedEnv: { POSTGRES_PASSWORD: 'p', POSTGRES_PORT: 1, CORE_API_PORT: 2 },
    overrideYaml: 'services:\n',
    compose: async (args) => {
      const joined = args.join(' ');
      state.composeCalls.push(joined);
      if (overrides.compose) return overrides.compose(args);
      if (args[0] === 'down') return overrides.downCode ?? 0;
      return 0;
    },
    request: async () => ({ status: 200, ok: true, body: '{}' }),
    writeExclusive: (path, contents) => { state.written.push({ path, contents }); if (overrides.writeThrows) throw new Error('exists'); },
    removeFile: (path) => { if (overrides.removeThrows) throw new Error('busy'); state.removed.push(path); },
    verify: async () => { state.verifyCalls += 1; if (overrides.verifyThrows) throw new Error('verify boom'); },
    configCheck: overrides.configCheck,
    log: () => {},
    error: () => {},
  };
  return { state, deps };
}

test('sanitizeComposeEnv drops COMPOSE_* and generated keys but keeps the rest', () => {
  const original = {
    PATH: 'x',
    COMPOSE_PROJECT_NAME: 'hijack',
    COMPOSE_FILE: 'other.yml',
    POSTGRES_PASSWORD: 'parent-secret',
    CORE_API_PORT: '9999',
    MOCK_ERP_WEBHOOK_SECRET: 'parent',
    HOME: 'h',
  };
  const env = sanitizeComposeEnv(original);
  assert.deepEqual(env, { PATH: 'x', HOME: 'h' });
  // The caller object is not mutated.
  assert.equal(original.POSTGRES_PASSWORD, 'parent-secret');
});

test('validateDistinctPorts rejects duplicates and invalid values', () => {
  assert.throws(() => validateDistinctPorts({ a: 1, b: 1 }), /Duplicate port/);
  assert.throws(() => validateDistinctPorts({ a: 0 }), /Invalid port/);
  assert.doesNotThrow(() => validateDistinctPorts({ a: 1, b: 2, c: 3, d: 4, e: 5 }));
});

test('buildOverrideYaml replaces every service publish on 127.0.0.1 with !override', () => {
  const yaml = buildOverrideYaml();
  for (const service of ['postgres', 'core-api', 'web', 'mock-erp', 'mock-purchasing']) {
    assert.match(yaml, new RegExp(`  ${service}:`));
  }
  assert.match(yaml, /ports: !override \["127\.0\.0\.1:\$\{POSTGRES_PORT\}:5432"\]/);
  assert.match(yaml, /ports: !override \["127\.0\.0\.1:\$\{MOCK_ERP_PORT\}:8081"\]/);
  assert.equal(yaml.includes('!reset'), false);
  assert.equal(yaml.includes('0.0.0.0'), false);
});

test('assertConfigPublishesOnLoopback fails closed on missing or non-loopback ports', () => {
  const good = JSON.stringify({
    services: {
      postgres: { ports: [{ host_ip: '127.0.0.1', published: '1', target: 5432 }] },
      'core-api': { ports: [{ host_ip: '127.0.0.1', published: '2', target: 8080 }] },
      web: { ports: [{ host_ip: '127.0.0.1', published: '3', target: 3000 }] },
      'mock-erp': { ports: [{ host_ip: '127.0.0.1', published: '4', target: 8081 }] },
      'mock-purchasing': { ports: [{ host_ip: '127.0.0.1', published: '5', target: 8082 }] },
    },
  });
  assert.doesNotThrow(() => assertConfigPublishesOnLoopback(good));
  assert.throws(() => assertConfigPublishesOnLoopback(JSON.stringify({ services: { postgres: { ports: [] } } })), /missing service|exactly one host port/);
  const wrongHost = JSON.parse(good);
  wrongHost.services.web.ports[0].host_ip = '0.0.0.0';
  assert.throws(() => assertConfigPublishesOnLoopback(JSON.stringify(wrongHost)), /not 127\.0\.0\.1/);
  const wrongTarget = JSON.parse(good);
  wrongTarget.services.web.ports[0].target = 9999;
  assert.throws(() => assertConfigPublishesOnLoopback(JSON.stringify(wrongTarget)), /expected 3000/);
});

test('decideOutcome fails on a cleanup error even when the workflow passed', () => {
  assert.deepEqual(decideOutcome({ failure: null, cleanupErrors: [] }), { ok: true, exitCode: 0, errors: [] });
  const cleanup = decideOutcome({ failure: null, cleanupErrors: ['docker compose down exited 7'] });
  assert.equal(cleanup.ok, false);
  assert.equal(cleanup.exitCode, 1);
  assert.match(cleanup.errors.join(' '), /down exited 7/);
  const thrown = decideOutcome({ failure: new Error('boom'), cleanupErrors: ['x'] });
  assert.equal(thrown.exitCode, 1);
  assert.deepEqual(thrown.errors, ['boom', 'x']);
});

test('a successful run writes, verifies, downs and removes its own files', async () => {
  const { state, deps } = composeHarness();
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, true);
  assert.equal(state.verifyCalls, 1);
  assert.deepEqual(state.written.map((entry) => entry.path), ['C:/tmp/env', 'C:/tmp/override']);
  assert.deepEqual(state.removed, ['C:/tmp/env', 'C:/tmp/override']);
  assert.ok(state.composeCalls.some((call) => call.startsWith('up --build')));
  assert.ok(state.composeCalls.some((call) => call.startsWith('down -v')));
});

test('a non-zero down exit fails the run even after a passing workflow', async () => {
  const { deps } = composeHarness({ downCode: 7 });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  assert.equal(outcome.exitCode, 1);
  assert.match(outcome.errors.join(' '), /down exited 7/);
});

test('a thrown down fails the run', async () => {
  const { deps } = composeHarness({ compose: (args) => { if (args[0] === 'down') throw new Error('down hang'); return 0; } });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  assert.match(outcome.errors.join(' '), /down failed: down hang/);
});

test('an up timeout/throw still attempts the scoped down', async () => {
  const { state, deps } = composeHarness({ compose: (args) => { if (args[0] === 'up') throw new Error('up hang'); return 0; } });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  assert.match(outcome.errors.join(' '), /up hang/);
  assert.ok(state.composeCalls.some((call) => call.startsWith('down -v')), 'down must still be attempted');
});

test('a partial up (non-zero exit) still attempts the scoped down', async () => {
  const { state, deps } = composeHarness({ compose: (args) => (args[0] === 'up' ? 1 : 0) });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  assert.match(outcome.errors.join(' '), /up failed with exit 1/);
  assert.ok(state.composeCalls.some((call) => call.startsWith('down -v')));
});

test('a missing docker executable fails closed and still attempts down', async () => {
  let calls = 0;
  const { state, deps } = composeHarness({ compose: () => { calls += 1; throw new ComposeSmokeError('could not start docker'); } });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  // config, then logs (failure path, also fails closed) and down are attempted.
  assert.equal(calls, 3, 'config, logs and down are all attempted');
  assert.ok(state.composeCalls.some((call) => call.startsWith('down -v')));
});

test('a verify failure still downs and fails', async () => {
  const { state, deps } = composeHarness({ verifyThrows: true });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  assert.match(outcome.errors.join(' '), /verify boom/);
  assert.ok(state.composeCalls.some((call) => call.startsWith('down -v')));
});

test('a configCheck (IPv4 publish) failure fails before up', async () => {
  const { state, deps } = composeHarness({ configCheck: async () => { throw new Error('not loopback'); } });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  assert.match(outcome.errors.join(' '), /not loopback/);
  assert.equal(state.composeCalls.some((call) => call.startsWith('up ')), false);
  assert.ok(state.composeCalls.some((call) => call.startsWith('down -v')));
});

test('a failed generated-file delete fails the run', async () => {
  const { deps } = composeHarness({ removeThrows: true });
  const outcome = await runComposeSmoke(deps);
  assert.equal(outcome.ok, false);
  assert.match(outcome.errors.join(' '), /env file cleanup failed/);
});

test('two concurrent runs use distinct projects and files and both succeed', async () => {
  const first = composeHarness({ project: 'im-p111-aaaa', envPath: 'C:/tmp/a.env', overridePath: 'C:/tmp/a.yml' });
  const second = composeHarness({ project: 'im-p111-bbbb', envPath: 'C:/tmp/b.env', overridePath: 'C:/tmp/b.yml' });
  const [a, b] = await Promise.all([runComposeSmoke(first.deps), runComposeSmoke(second.deps)]);
  assert.equal(a.ok, true);
  assert.equal(b.ok, true);
  assert.notDeepEqual(first.state.written.map((entry) => entry.path), second.state.written.map((entry) => entry.path));
});

function fakeChild() {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  child.kill = () => true;
  return child;
}

test('runStreaming resolves a normal close', async () => {
  const child = fakeChild();
  const run = createRunStreaming({ spawnImpl: () => child, stdout: { write() {} }, stderr: { write() {} } });
  const pending = run('node', ['-e', ''], { timeoutMs: 1000 });
  child.emit('close', 0);
  assert.equal(await pending, 0);
});

test('runStreaming kills and rejects on timeout even if the child then closes', async () => {
  const child = fakeChild();
  const run = createRunStreaming({ spawnImpl: () => child, killGraceMs: 500, stdout: { write() {} }, stderr: { write() {} } });
  const pending = run('node', ['-e', 'hang'], { timeoutMs: 10 });
  await new Promise((resolve) => setTimeout(resolve, 30));
  child.emit('close', 0);
  await assert.rejects(pending, /timed out after 10ms/);
});

test('runStreaming reports an unclosed child after the kill grace', async () => {
  const child = fakeChild();
  const run = createRunStreaming({ spawnImpl: () => child, killGraceMs: 20, stdout: { write() {} }, stderr: { write() {} } });
  await assert.rejects(run('node', ['-e', 'hang'], { timeoutMs: 10 }), /did not close within 20ms/);
});

test('runStreaming preserves a kill failure', async () => {
  const child = fakeChild();
  child.kill = () => { throw new Error('kill denied'); };
  const run = createRunStreaming({ spawnImpl: () => child, killGraceMs: 20, stdout: { write() {} }, stderr: { write() {} } });
  await assert.rejects(run('node', ['-e', 'hang'], { timeoutMs: 10 }), /kill denied/);
});

test('runStreaming rejects when the executable cannot start', async () => {
  const run = createRunStreaming({ spawnImpl: () => { throw new Error('ENOENT'); } });
  await assert.rejects(run('definitely-missing', [], { timeoutMs: 100 }), /could not start definitely-missing/);
});
