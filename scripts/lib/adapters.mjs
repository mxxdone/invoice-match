// Production adapters for the P1-10 verification script, importable on their
// own so the real spawn/error/exit/close contract can be reproduced in tests.
//
// The probe, HTTP readiness/verify, DB and service URLs, server binds and the
// docker publish all use the fixed IPv4 loopback 127.0.0.1, so an unrelated
// IPv6-only listener can never be mistaken for our child and receive seed
// writes. (The mock-purchasing fixture itself listens on 0.0.0.0, but it is
// only ever reached and probed through 127.0.0.1.)

import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { join } from 'node:path';

export const LOOPBACK = '127.0.0.1';

export function loopbackUrl(port) {
  return `http://${LOOPBACK}:${port}`;
}

export function createPortProbe({ host = LOOPBACK } = {}) {
  return (port) =>
    new Promise((resolve) => {
      const server = createServer();
      server.once('error', (error) => resolve(error.code === 'EADDRINUSE'));
      server.once('listening', () => server.close(() => resolve(false)));
      server.listen(port, host);
    });
}

export function createDocker({ timeoutMs = 60000 } = {}) {
  const run = (args) => {
    const result = spawnSync('docker', args, { encoding: 'utf8', timeout: timeoutMs });
    return { code: result.status ?? 1, stdout: result.stdout ?? '', stderr: result.stderr ?? '' };
  };
  return {
    run: async (args) => run(args),
    exec: async (id, args) => run(['exec', id, ...args]),
    stop: async (id) => run(['stop', id]),
  };
}

export function createRequest({ timeoutMs = 8000 } = {}) {
  return async (url, init = {}) => {
    const response = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
    const body = await response.text();
    return { ok: response.ok, status: response.status, body };
  };
}

export function startChild(spec, { timeoutMs = 5000 } = {}) {
  const state = { error: null, exited: false, closed: false };
  let child = null;
  try {
    // Explicit env: the caller's environment is never mutated; PATH is
    // inherited only so the executable can be resolved.
    child = spawn(spec.cmd, spec.args, {
      cwd: spec.cwd,
      env: { ...process.env, ...spec.env },
      // Default is quiet; P110_CHILD_STDIO=inherit is a diagnostic switch that
      // lets a failing child's own log be read without changing the normal run.
      stdio: spec.stdio ?? process.env.P110_CHILD_STDIO ?? 'ignore',
      windowsHide: true,
    });
  } catch (error) {
    state.error = error;
    state.exited = true;
  }

  const closed = new Promise((resolve) => {
    if (!child) {
      resolve();
      return;
    }
    // The error listener is mandatory: without it a missing executable or an
    // invalid cwd becomes an unhandled 'error' event.
    child.once('error', (error) => {
      state.error = error;
      state.exited = true;
      resolve();
    });
    child.once('exit', () => {
      state.exited = true;
    });
    child.once('close', () => {
      state.closed = true;
      resolve();
    });
  });
  closed.catch(() => {});

  return {
    name: spec.name,
    alive() {
      return child !== null && state.error === null && !state.exited && !state.closed;
    },
    async stop() {
      if (!child || state.closed || state.exited) {
        return { ok: true };
      }
      try {
        child.kill();
      } catch (error) {
        return { ok: false, error: String(error) };
      }
      const result = await Promise.race([
        closed.then(() => 'closed'),
        new Promise((resolve) => setTimeout(() => resolve('timeout'), timeoutMs)),
      ]);
      if (result === 'timeout') {
        try {
          child.kill('SIGKILL');
        } catch {
          // best effort
        }
        return { ok: false, error: 'child did not exit within timeout' };
      }
      return { ok: true };
    },
  };
}

// Verification-only fifth identity: a submitter who is also an approver and
// operator, so the browser check can obtain a real server self-approval 403.
// Passwords reuse the already-committed local/demo BCrypt hashes; this only
// extends the throwaway child's configuration and never changes production or
// local files.
const DUAL_ROLE_USER = {
  username: 'dual',
  password: '{bcrypt}$2a$10$zI98Q/Kc88bhkspHb/BRneFPm1bxu5d4ciVHaNBm/nUFEy9FYKE/O',
  roles: ['SUBMITTER', 'APPROVER', 'OPERATOR'],
};

function demoUsersOverride() {
  return JSON.stringify({
    security: {
      demo: {
        users: [
          { username: 'submitter', password: '{bcrypt}$2a$10$dy82eO2.Xqf1r/xnYb0d.umTy.YPhylxIHoWLR5gi.conT.c3lpHm', roles: ['SUBMITTER'] },
          { username: 'submitter2', password: '{bcrypt}$2a$10$yU7d64ayCZUnbgPCLo0aC.xeH/jKDqJfVf8XjEK.9/6eXfXTeKD2S', roles: ['SUBMITTER'] },
          { username: 'approver', password: '{bcrypt}$2a$10$zI98Q/Kc88bhkspHb/BRneFPm1bxu5d4ciVHaNBm/nUFEy9FYKE/O', roles: ['APPROVER'] },
          { username: 'operator', password: '{bcrypt}$2a$10$43jqVAOkzegqkQVkGJi7luHB.MK5GGe1uexfRnH9C07fWEzVdcsfq', roles: ['OPERATOR'] },
          DUAL_ROLE_USER,
        ],
      },
    },
  });
}

// buildConfig shares the isolated stack definition. The default (P1-10) shape
// starts PG + mock-purchasing + core-api + web and leaves the payment relay
// fail-closed. `withErp` additionally hosts the P1-11 in-process Mock ERP
// fixture and opts the throwaway core-api into the signed payment relay, all on
// the isolated loopback ports; it changes nothing for callers that omit it.
export function buildConfig({
  ports,
  repo,
  pgPassword,
  withErp = false,
  webhookSecret,
  relayInterval = '1s',
} = {}) {
  const coreEnv = {
    DB_URL: `jdbc:postgresql://${LOOPBACK}:${ports.pg}/invoice_match`,
    DB_USER: 'invoice_match',
    DB_PASSWORD: pgPassword,
    SPRING_PROFILES_ACTIVE: 'local',
    PURCHASING_BASE_URL: loopbackUrl(ports.mock),
    SPRING_APPLICATION_JSON: demoUsersOverride(),
  };
  if (withErp) {
    coreEnv.ERP_BASE_URL = loopbackUrl(ports.erp);
    coreEnv.PAYMENT_EXPORT_RELAY_ENABLED = 'true';
    coreEnv.PAYMENT_EXPORT_INTERVAL = relayInterval;
    // The fault scenario delays the ERP response to observe the committed
    // SENDING window; the request deadline must stay comfortably above that
    // delay so a late 2xx (not a timeout) is what races the webhook.
    coreEnv.PAYMENT_EXPORT_CONNECT_TIMEOUT = '2s';
    coreEnv.PAYMENT_EXPORT_REQUEST_TIMEOUT = '10s';
    coreEnv.MOCK_ERP_WEBHOOK_SECRET = webhookSecret;
  }

  const children = [
    {
      name: 'mock-purchasing',
      cmd: 'node',
      args: ['server.js'],
      cwd: join(repo, 'mock-purchasing'),
      env: { PORT: String(ports.mock) },
      readiness: { url: `${loopbackUrl(ports.mock)}/health`, verify: (body) => body.includes('"status":"UP"') },
    },
  ];
  if (withErp) {
    children.push({
      name: 'mock-erp',
      inProcess: 'erp',
      host: LOOPBACK,
      port: ports.erp,
      webhookUrl: `${loopbackUrl(ports.core)}/webhooks/mock-erp/payment-results`,
      webhookSecret,
      dropResponse: false,
      autoWebhook: false,
      readiness: { url: `${loopbackUrl(ports.erp)}/health`, verify: (body) => body.includes('"status":"UP"') },
    });
  }
  children.push(
    {
      name: 'core-api',
      cmd: 'java',
      args: ['-jar', join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar'), `--server.port=${ports.core}`, `--server.address=${LOOPBACK}`],
      cwd: join(repo, 'core-api'),
      env: coreEnv,
      readiness: { url: `${loopbackUrl(ports.core)}/actuator/health`, verify: (body) => body.includes('"status":"UP"') },
    },
    {
      name: 'web',
      cmd: 'node',
      args: ['server.js'],
      cwd: join(repo, 'web', '.next', 'standalone'),
      env: { CORE_API_URL: loopbackUrl(ports.core), PORT: String(ports.web), HOSTNAME: LOOPBACK },
      readiness: { url: `${loopbackUrl(ports.web)}/login` },
    },
  );

  return {
    ports,
    images: { postgres: 'postgres:18-alpine' },
    pgEnv: { POSTGRES_DB: 'invoice_match', POSTGRES_USER: 'invoice_match', POSTGRES_PASSWORD: pgPassword },
    pgPublish: `${LOOPBACK}:${ports.pg}:5432`,
    pgReadyArgs: ['pg_isready', '-U', 'invoice_match', '-d', 'invoice_match'],
    readinessTimeoutMs: 90000,
    children,
  };
}
