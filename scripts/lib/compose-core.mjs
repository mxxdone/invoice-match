// Testable core of the P1-11 clean Compose smoke.
//
// All external effects are injected so the safety contract can be reproduced
// with stubs: exclusive env-file creation, explicit 127.0.0.1 publishing,
// environment isolation, bounded process termination, and — critically — a
// cleanup failure (down non-zero/throw, an unclosed child, or a failed
// generated-file delete) must force a non-zero exit even when the workflow
// passed. This mirrors the P1-10 `verify-core.mjs`/`entry.mjs` contract.

import { spawn } from 'node:child_process';

export class ComposeSmokeError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ComposeSmokeError';
  }
}

// Host/interpolation variables that must never leak from the caller into the
// compose subprocess: the generated `--env-file` is the only source for them,
// and COMPOSE_* can redirect the project/file. The caller's process env is
// never mutated; a copy without these keys is what the child sees.
export const GENERATED_ENV_KEYS = Object.freeze([
  'POSTGRES_PASSWORD',
  'MOCK_ERP_WEBHOOK_SECRET',
  'POSTGRES_PORT',
  'CORE_API_PORT',
  'WEB_PORT',
  'MOCK_ERP_PORT',
  'MOCK_PURCHASING_PORT',
]);

export function sanitizeComposeEnv(baseEnv) {
  const env = {};
  for (const [key, value] of Object.entries(baseEnv)) {
    if (key.startsWith('COMPOSE_')) continue;
    if (GENERATED_ENV_KEYS.includes(key)) continue;
    env[key] = value;
  }
  return env;
}

export function validateDistinctPorts(ports) {
  const seen = new Map();
  for (const [name, value] of Object.entries(ports)) {
    if (!Number.isInteger(value) || value < 1 || value > 65535) {
      throw new ComposeSmokeError(`Invalid port for ${name}: ${value}`);
    }
    if (seen.has(value)) {
      throw new ComposeSmokeError(`Duplicate port ${value} for ${seen.get(value)} and ${name}`);
    }
    seen.set(value, name);
  }
}

const PUBLISH = Object.freeze({
  postgres: ['POSTGRES_PORT', 5432],
  'core-api': ['CORE_API_PORT', 8080],
  web: ['WEB_PORT', 3000],
  'mock-erp': ['MOCK_ERP_PORT', 8081],
  'mock-purchasing': ['MOCK_PURCHASING_PORT', 8082],
});

// A generated override that REPLACES the published port lists with explicit
// 127.0.0.1 bindings. Compose merges sequence entries by appending (which would
// keep the base 0.0.0.0 publish and collide), so `!override` is required to
// replace the list; `!reset` would clear the ports entirely.
export function buildOverrideYaml() {
  const lines = ['services:'];
  for (const [service, [envKey, target]] of Object.entries(PUBLISH)) {
    lines.push(`  ${service}:`);
    lines.push('    ports: !override ["127.0.0.1:${' + envKey + '}:' + target + '"]');
  }
  return lines.join('\n') + '\n';
}

export function envFileText(generatedEnv) {
  return Object.entries(generatedEnv).map(([key, value]) => `${key}=${value}`).join('\n') + '\n';
}

export function decideOutcome({ failure, cleanupErrors = [] }) {
  const errors = [];
  if (failure) errors.push(failure instanceof Error ? failure.message : String(failure));
  errors.push(...cleanupErrors);
  if (errors.length > 0) {
    return { ok: false, exitCode: 1, errors };
  }
  return { ok: true, exitCode: 0, errors: [] };
}

// Bounded, streaming process runner. On timeout it kills the child and waits a
// bounded grace for `close`; a kill failure or an unclosed child is preserved
// in the error rather than silently ignored.
export function createRunStreaming({ spawnImpl = spawn, killGraceMs = 10000, stdout = process.stdout, stderr = process.stderr } = {}) {
  return function runStreaming(command, args, { timeoutMs, env, cwd, stdout: stdoutOverride, stderr: stderrOverride } = {}) {
    const out = stdoutOverride ?? stdout;
    const err = stderrOverride ?? stderr;
    return new Promise((resolvePromise, rejectPromise) => {
      let child;
      try {
        child = spawnImpl(command, args, { cwd, env, windowsHide: true, shell: false });
      } catch (error) {
        rejectPromise(new ComposeSmokeError(`could not start ${command}: ${error.message}`));
        return;
      }
      let settled = false;
      let killError = null;
      let timedOut = false;
      const timeoutError = new ComposeSmokeError(`timed out after ${timeoutMs}ms: ${command} ${args.join(' ')}`);
      const fail = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeoutTimer);
        clearTimeout(killTimer);
        rejectPromise(error);
      };
      const finish = (code) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeoutTimer);
        clearTimeout(killTimer);
        resolvePromise(code);
      };
      let killTimer = null;
      if (child.stdout) child.stdout.on('data', (data) => out.write(data));
      if (child.stderr) child.stderr.on('data', (data) => err.write(data));
      child.once('error', (error) => {
        if (timedOut) {
          fail(new ComposeSmokeError(`${timeoutError.message} (kill: ${killError ?? 'ok'}); ${error.message}`));
        } else {
          fail(error);
        }
      });
      child.once('close', (code) => {
        if (timedOut) {
          fail(new ComposeSmokeError(`${timeoutError.message}; the process closed after ${killError ? `kill error ${killError}` : 'the timeout kill'}`));
        } else {
          finish(code ?? 1);
        }
      });
      const timeoutTimer = setTimeout(() => {
        timedOut = true;
        try {
          child.kill('SIGKILL');
        } catch (error) {
          killError = error.message;
        }
        killTimer = setTimeout(() => {
          fail(new ComposeSmokeError(`${timeoutError.message}; the process did not close within ${killGraceMs}ms (kill: ${killError ?? 'ok'})`));
        }, killGraceMs);
      }, timeoutMs);
    });
  };
}

export const runStreaming = createRunStreaming();

// Capture a short-lived command's stdout (used only for `compose config`, which
// is instantaneous) while still streaming it live and bounding the deadline.
export function createRunCapture({ run = runStreaming } = {}) {
  return async function runCapture(command, args, options = {}) {
    const chunks = [];
    const sink = { write: (data) => { chunks.push(Buffer.isBuffer(data) ? data.toString('utf8') : String(data)); return true; } };
    const code = await run(command, args, { ...options, stdout: sink, stderr: sink });
    return { code, stdout: chunks.join('') };
  };
}

// Fail-closed: every expected service must have exactly one loopback published
// port targeting its container port. An empty/cleared list is a failure, not a
// vacuous pass.
export function assertConfigPublishesOnLoopback(configJson) {
  const parsed = JSON.parse(configJson);
  const services = parsed.services ?? {};
  for (const [service, [, target]] of Object.entries(PUBLISH)) {
    const configured = services[service];
    if (!configured) {
      throw new ComposeSmokeError(`compose config is missing service ${service}`);
    }
    const ports = configured.ports ?? [];
    if (ports.length !== 1) {
      throw new ComposeSmokeError(`compose service ${service} must publish exactly one host port, found ${ports.length}`);
    }
    const port = ports[0];
    const host = port.host_ip ?? port.hostIp;
    const publishedTarget = Number(port.target ?? port.container_port);
    if (host !== '127.0.0.1') {
      throw new ComposeSmokeError(`compose service ${service} publishes on ${host ?? '0.0.0.0'}, not 127.0.0.1`);
    }
    if (publishedTarget !== target) {
      throw new ComposeSmokeError(`compose service ${service} publishes container port ${publishedTarget}, expected ${target}`);
    }
  }
}

/**
 * Lifecycle orchestrator. `verify` runs after a successful `up`; every cleanup
 * failure is collected and turned into a non-zero exit by decideOutcome, even
 * when the workflow passed.
 */
export async function runComposeSmoke({
  project,
  envPath,
  overridePath,
  generatedEnv,
  overrideYaml,
  compose,
  request,
  writeExclusive,
  removeFile,
  verify,
  configCheck,
  log = () => {},
  error = () => {},
  upTimeoutMs = 1500000,
  logsTimeoutMs = 120000,
  downTimeoutMs = 300000,
  configTimeoutMs = 60000,
}) {
  const created = { env: false, override: false, up: false };
  const cleanupErrors = [];
  let failure = null;

  const remove = (path, label) => {
    try {
      removeFile(path);
    } catch (removeError) {
      cleanupErrors.push(`${label} cleanup failed: ${removeError instanceof Error ? removeError.message : String(removeError)}`);
    }
  };

  try {
    writeExclusive(envPath, envFileText(generatedEnv));
    created.env = true;
    writeExclusive(overridePath, overrideYaml);
    created.override = true;

    const configCode = await compose(['config', '--quiet'], configTimeoutMs);
    if (configCode !== 0) {
      throw new ComposeSmokeError(`docker compose config exited ${configCode}`);
    }
    if (configCheck) {
      await configCheck();
    }

    const upCode = await compose(['up', '--build', '-d', '--wait'], upTimeoutMs);
    if (upCode !== 0) {
      throw new ComposeSmokeError(`docker compose up failed with exit ${upCode}`);
    }
    created.up = true;
    log(`PASS compose up --build --wait (project ${project})`);

    await verify({ compose, request, project, generatedEnv, log });
  } catch (caught) {
    failure = caught instanceof Error ? caught : new ComposeSmokeError(String(caught));
  } finally {
    if (failure) {
      try {
        await compose(['logs', '--no-color'], logsTimeoutMs);
      } catch (logError) {
        error(`WARN compose logs unavailable: ${logError instanceof Error ? logError.message : String(logError)}`);
      }
    }
    try {
      const down = await compose(['down', '-v', '--remove-orphans'], downTimeoutMs);
      if (down !== 0) {
        cleanupErrors.push(`docker compose down exited ${down}`);
      } else if (created.up) {
        log('INFO compose project owned resources removed');
      }
    } catch (downError) {
      cleanupErrors.push(`docker compose down failed: ${downError instanceof Error ? downError.message : String(downError)}`);
    }
    if (created.env) remove(envPath, 'env file');
    if (created.override) remove(overridePath, 'override file');
  }

  return decideOutcome({ failure, cleanupErrors });
}
