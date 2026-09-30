// P1-10 live-slice verification CLI.
//
// Starts only throwaway resources it creates (ephemeral PostgreSQL container,
// mock-purchasing, the built core-api jar, the built web standalone), owns their
// PIDs/container ids, waits for child-aware readiness with a finite timeout,
// runs the read-API checks, and always cleans up only what it created. It never
// stops an existing container, never mutates the caller environment and never
// logs a credential. Everything the script probes or targets uses the fixed
// IPv4 loopback 127.0.0.1 (the mock-purchasing fixture itself listens on
// 0.0.0.0, but it is only ever reached through 127.0.0.1).
//
// Prerequisites:
//   cd core-api; ./gradlew bootJar
//   cd web; npm ci; npm run build
//
// Usage:
//   node scripts/verify-p1-10.mjs
//   node scripts/verify-p1-10.mjs --core-port 8080 --web-port 3100

import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, cpSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { runVerification } from './lib/verify-core.mjs';
import { runEntry } from './lib/entry.mjs';
import { buildConfig, createDocker, createPortProbe, createRequest, startChild } from './lib/adapters.mjs';
import { runChecks } from './lib/checks.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');

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
const webStandalone = join(repo, 'web', '.next', 'standalone');
if (!existsSync(coreJar)) {
  console.error(`Missing ${coreJar}. Run: cd core-api; ./gradlew bootJar`);
  process.exit(1);
}
if (!existsSync(join(webStandalone, 'server.js'))) {
  console.error('Missing web standalone build. Run: cd web; npm run build');
  process.exit(1);
}
if (!existsSync(join(webStandalone, '.next', 'static'))) {
  mkdirSync(join(webStandalone, '.next'), { recursive: true });
  cpSync(join(repo, 'web', '.next', 'static'), join(webStandalone, '.next', 'static'), { recursive: true });
}

const config = buildConfig({ ports, repo, pgPassword: randomUUID() });

const outcome = await runEntry({
  run: () =>
    runVerification({
      config,
      isPortOpen: createPortProbe(),
      docker: createDocker(),
      startChild,
      request: createRequest(),
      now: Date.now,
      sleep: (ms) => new Promise((done) => setTimeout(done, ms)),
      log: (message) => console.log(message),
      verify: runChecks,
    }),
});

process.exitCode = outcome.exitCode;
