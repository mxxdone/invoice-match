// P1-10 live-slice verification (Node, testable core in ./lib/verify-core.mjs).
//
// Starts only throwaway resources it creates (ephemeral PostgreSQL container,
// mock-purchasing, the built core-api jar, the built web standalone), owns their
// PIDs/container ids, waits for child-aware readiness with a finite timeout,
// runs the read-API checks, and always cleans up only what it created. It never
// stops an existing container, never mutates the caller's environment and never
// logs a credential.
//
// Prerequisites:
//   cd core-api; ./gradlew bootJar
//   cd web; npm ci; npm run build
//
// Usage:
//   node scripts/verify-p1-10.mjs
//   node scripts/verify-p1-10.mjs --core-port 8080 --web-port 3100

import { spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { runVerification } from './lib/verify-core.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');

const HTTP_TIMEOUT_MS = 8000;
const DOCKER_TIMEOUT_MS = 60000;
const READINESS_TIMEOUT_MS = 90000;
const READY_INTERVAL_MS = 500;

function parseFlag(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  if (index >= 0 && process.argv[index + 1]) return Number(process.argv[index + 1]);
  return fallback;
}

const ports = {
  pg: parseFlag('pg-port', 55433),
  mock: parseFlag('mock-port', 8082),
  core: parseFlag('core-port', 8080),
  web: parseFlag('web-port', 3100),
};

const coreJar = join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar');
const webServer = join(repo, 'web', '.next', 'standalone', 'server.js');

function isPortOpen(port) {
  return new Promise((ready) => {
    const server = createServer();
    server.once('error', (error) => ready(error.code === 'EADDRINUSE'));
    server.once('listening', () => server.close(() => ready(false)));
    server.listen(port, '127.0.0.1');
  });
}

const docker = {
  async run(args) {
    const result = spawnSync('docker', args, { encoding: 'utf8', timeout: DOCKER_TIMEOUT_MS });
    return { code: result.status ?? 1, stdout: result.stdout ?? '', stderr: result.stderr ?? '' };
  },
  async exec(id, args) {
    const result = spawnSync('docker', ['exec', id, ...args], { encoding: 'utf8', timeout: DOCKER_TIMEOUT_MS });
    return { code: result.status ?? 1, stdout: result.stdout ?? '', stderr: result.stderr ?? '' };
  },
  async stop(id) {
    spawnSync('docker', ['stop', id], { encoding: 'utf8', timeout: DOCKER_TIMEOUT_MS });
  },
};

function startChild(spec) {
  // Explicit env: the caller's environment is never mutated, and each child
  // only receives the keys it needs.
  const child = spawn(spec.cmd, spec.args, {
    cwd: spec.cwd,
    env: { ...process.env, ...spec.env },
    stdio: 'ignore',
    windowsHide: true,
  });
  return {
    name: spec.name,
    alive: () => child.exitCode === null && !child.killed,
    kill: () => { try { child.kill(); } catch { /* best effort */ } },
  };
}

async function request(url, init = {}) {
  const response = await fetch(url, { ...init, signal: AbortSignal.timeout(HTTP_TIMEOUT_MS) });
  const body = await response.text();
  return { ok: response.ok, status: response.status, body };
}

function basic(user, password) {
  return 'Basic ' + Buffer.from(`${user}:${password}`, 'utf8').toString('base64');
}

async function verify({ request: http, config, guard, log }) {
  const core = `http://localhost:${config.ports.core}`;
  const web = `http://localhost:${config.ports.web}`;
  const approver = { authorization: basic('approver', 'approver-pass') };
  const submitter = { authorization: basic('submitter', 'submitter-pass') };

  async function check(name, condition, detail) {
    if (!condition) throw new Error(`FAIL ${name}: ${detail}`);
    log(`PASS ${name}`);
  }

  const anon = await http(`${core}/api/me`);
  await check('anonymous /api/me is 401', anon.status === 401, `got ${anon.status}`);

  const me = await http(`${core}/api/me`, { headers: approver });
  await check('approver /api/me is 200', me.status === 200, `got ${me.status}`);
  await check('approver role present', me.body.includes('APPROVER'), 'missing APPROVER');

  // Re-confirm the isolated core target (our child on our free port) is UP
  // before writing any seed data.
  guard();
  const health = await http(`${core}/actuator/health`);
  await check('isolated core health is UP', health.status === 200 && health.body.includes('"status":"UP"'), `got ${health.status}`);

  guard();
  const created = JSON.parse((await http(`${core}/api/invoice-cases`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-create', supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber: 'INV-VERIFY-9' }),
  })).body);

  guard();
  const drafted = JSON.parse((await http(`${core}/api/invoice-cases/${created.id}/draft`, {
    method: 'PUT',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-draft', expectedCaseVersion: created.version, lines: [{ lineNumber: 1, rawItemName: 'Copy Paper', quantity: 5, unitPrice: 2500, confirmedItemId: 'ITEM-A4-80' }] }),
  })).body);

  guard();
  await http(`${core}/api/invoice-cases/${created.id}/submit`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-submit', expectedCaseVersion: drafted.version }),
  });

  const partial = JSON.parse((await http(`${core}/api/invoice-cases?invoiceNumber=VERIFY&size=20`, { headers: approver })).body);
  await check('partial invoice search finds the case', partial.totalItems === 1, `got ${partial.totalItems}`);

  const kstDay = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  const range = JSON.parse((await http(`${core}/api/invoice-cases?submittedFrom=${kstDay}T00:00:00.000000%2B09:00&submittedTo=${kstDay}T23:59:59.999999%2B09:00&size=100`, { headers: approver })).body);
  await check('KST inclusive day range finds the case', (range.items ?? []).some((item) => item.invoiceNumber === 'INV-VERIFY-9'), 'case not in range');

  const proxyAnon = await http(`${web}/backend/api/me`);
  await check('proxy anonymous /api/me is 401', proxyAnon.status === 401, `got ${proxyAnon.status}`);
  const proxyBlocked = await http(`${web}/backend/api/invoice-cases/${created.id}/approve`);
  await check('proxy refuses a non-allowlisted path', proxyBlocked.status === 404, `got ${proxyBlocked.status}`);

  return {};
}

const pgPassword = randomUUID();

const config = {
  ports,
  images: { postgres: 'postgres:18-alpine' },
  pgEnv: { POSTGRES_DB: 'invoice_match', POSTGRES_USER: 'invoice_match', POSTGRES_PASSWORD: pgPassword },
  pgReadyArgs: ['pg_isready', '-U', 'invoice_match', '-d', 'invoice_match'],
  readinessTimeoutMs: READINESS_TIMEOUT_MS,
  children: [
    {
      name: 'mock-purchasing',
      cmd: 'node',
      args: ['server.js'],
      cwd: join(repo, 'mock-purchasing'),
      env: { PORT: String(ports.mock) },
      readiness: { url: `http://localhost:${ports.mock}/health`, verify: (body) => body.includes('"status":"UP"') },
    },
    {
      name: 'core-api',
      cmd: 'java',
      args: ['-jar', coreJar, `--server.port=${ports.core}`],
      cwd: join(repo, 'core-api'),
      env: {
        DB_URL: `jdbc:postgresql://localhost:${ports.pg}/invoice_match`,
        DB_USER: 'invoice_match',
        DB_PASSWORD: pgPassword,
        SPRING_PROFILES_ACTIVE: 'local',
        PURCHASING_BASE_URL: `http://localhost:${ports.mock}`,
      },
      readiness: { url: `http://localhost:${ports.core}/actuator/health`, verify: (body) => body.includes('"status":"UP"') },
    },
    {
      name: 'web',
      cmd: 'node',
      args: ['server.js'],
      cwd: join(repo, 'web', '.next', 'standalone'),
      env: { CORE_API_URL: `http://localhost:${ports.core}`, PORT: String(ports.web), HOSTNAME: '0.0.0.0' },
      readiness: { url: `http://localhost:${ports.web}/login` },
    },
  ],
};

const result = await runVerification({
  config,
  isPortOpen,
  docker,
  startChild,
  request,
  now: Date.now,
  sleep: (ms) => new Promise((done) => setTimeout(done, ms)),
  log: (message) => console.log(message),
  verify,
});

console.log(result.ok ? 'ALL CHECKS PASSED' : 'VERIFICATION FAILED');
