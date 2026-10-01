// Testable core of the P1-10 verification script.
//
// All external effects (port probing, docker, child processes, HTTP) are
// injected so the safety contract can be reproduced with stubs in unit tests:
// port preflight, docker exit-code/id ownership, child-exit-aware readiness,
// seed gating, bounded cleanup and cleanup-error reporting.

import { randomUUID } from 'node:crypto';

export class VerifyError extends Error {
  constructor(message) {
    super(message);
    this.name = 'VerifyError';
  }
}

export function validatePorts(ports) {
  const seen = new Map();
  for (const [name, value] of Object.entries(ports)) {
    if (!Number.isInteger(value) || value < 1 || value > 65535) {
      throw new VerifyError(`Invalid port for ${name}: ${value}`);
    }
    if (seen.has(value)) {
      throw new VerifyError(`Duplicate port ${value} used by both ${seen.get(value)} and ${name}`);
    }
    seen.set(value, name);
  }
}

export async function assertPortsFree(ports, isPortOpen) {
  for (const [name, value] of Object.entries(ports)) {
    if (await isPortOpen(value)) {
      throw new VerifyError(`Preflight failed: ${name} port ${value} is already in use`);
    }
  }
}

export function randomContainerName(prefix) {
  return `${prefix}-${randomUUID()}`;
}

export async function dockerRun({ docker, name, image, env, publish, containerPort = 5432 }) {
  const args = ['run', '-d', '--rm', '--name', name];
  for (const [key, value] of Object.entries(env)) {
    args.push('-e', `${key}=${value}`);
  }
  args.push('-p', publish ?? `${containerPort}`, image);
  const result = await docker.run(args);
  if (result.code !== 0) {
    throw new VerifyError(`docker run failed (exit ${result.code}): ${(result.stderr || '').trim() || 'no stderr'}`);
  }
  const id = (result.stdout || '').trim();
  if (!id) {
    throw new VerifyError('docker run reported success but returned no container id');
  }
  return { name, id };
}

function childAlive(child) {
  if (typeof child.alive === 'function') return child.alive();
  return !child.killed && child.exitCode === null;
}

export async function waitForHttp({
  request,
  url,
  isChildAlive,
  verify,
  timeoutMs,
  intervalMs = 500,
  now = Date.now,
  sleep,
}) {
  const deadline = now() + timeoutMs;
  for (;;) {
    if (!isChildAlive()) {
      throw new VerifyError(`Child exited before ${url} became ready`);
    }
    try {
      const response = await request(url);
      if (response.ok && (!verify || verify(response.body, response.status))) {
        return response;
      }
    } catch {
      // retry until the deadline
    }
    if (now() >= deadline) {
      throw new VerifyError(`Readiness timeout for ${url}`);
    }
    await sleep(intervalMs);
  }
}

export async function waitForDockerReady({ docker, id, args, timeoutMs, intervalMs = 500, now = Date.now, sleep }) {
  const deadline = now() + timeoutMs;
  for (;;) {
    const result = await docker.exec(id, args);
    if (result.code === 0) return;
    if (now() >= deadline) {
      throw new VerifyError(`Container ${id} did not become ready`);
    }
    await sleep(intervalMs);
  }
}

async function cleanupCreated(docker, created) {
  const errors = [];
  for (const child of created.children) {
    try {
      const outcome = typeof child.stop === 'function' ? await child.stop() : { ok: true };
      if (outcome && outcome.ok === false) {
        errors.push(`stop ${child.name}: ${outcome.error ?? 'unknown'}`);
      }
    } catch (error) {
      errors.push(`stop ${child.name}: ${String(error)}`);
    }
  }
  for (const id of created.containers) {
    try {
      const result = await docker.stop(id);
      if (result && typeof result.code === 'number' && result.code !== 0) {
        errors.push(`docker stop ${id}: exit ${result.code}`);
      }
    } catch (error) {
      errors.push(`docker stop ${id}: ${String(error)}`);
    }
  }
  return errors;
}

export async function runVerification(deps) {
  const { config, isPortOpen, docker, startChild, request, now, sleep, log = () => {} } = deps;
  const created = { containers: [], children: [] };
  let outcome;
  try {
    validatePorts(config.ports);
    await assertPortsFree(config.ports, isPortOpen);

    const name = randomContainerName('im-verify-pg');
    const pg = await dockerRun({
      docker,
      name,
      image: config.images.postgres,
      env: config.pgEnv,
      publish: config.pgPublish,
    });
    created.containers.push(pg.id);
    await waitForDockerReady({
      docker,
      id: pg.id,
      args: config.pgReadyArgs,
      timeoutMs: config.readinessTimeoutMs,
      now,
      sleep,
    });

    for (const spec of config.children) {
      // Await so an async adapter may finish wiring listeners, then register
      // immediately so a later spawn/exit can never escape cleanup.
      const child = await startChild(spec);
      created.children.push(child);
      await waitForHttp({
        request,
        url: spec.readiness.url,
        isChildAlive: () => childAlive(child),
        verify: spec.readiness.verify,
        timeoutMs: config.readinessTimeoutMs,
        now,
        sleep,
      });
    }

    const guard = () => {
      for (const child of created.children) {
        if (!childAlive(child)) {
          throw new VerifyError(`Child ${child.name} is not running; refusing to seed`);
        }
      }
    };
    guard();
    outcome = {
      ok: true,
      value: await deps.verify({
        request,
        config,
        guard,
        log,
        // Child adapters and created container ids let a P1-11 scenario reach
        // its in-process ERP fixture and assert persisted database state. The
        // existing cleanup still owns and stops exactly these resources.
        children: created.children,
        containers: created.containers.slice(),
        docker,
      }),
    };
  } catch (error) {
    outcome = { ok: false, failure: error };
  }

  const cleanupErrors = await cleanupCreated(docker, created);
  if (cleanupErrors.length > 0) {
    if (outcome.ok) {
      return { ok: false, cleanupErrors };
    }
    outcome.failure.message += ` (cleanup also failed: ${cleanupErrors.join('; ')})`;
  }
  if (!outcome.ok) {
    throw outcome.failure;
  }
  return { ok: true, ...outcome.value };
}
