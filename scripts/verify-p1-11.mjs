// P1-11 Phase 1 integration acceptance CLI.
//
// It starts only throwaway resources it creates (an ephemeral PostgreSQL
// container, mock-purchasing, the built core-api jar with the in-process Mock
// ERP fixture and the built web standalone), records and owns their
// PIDs/container ids, waits for child-aware readiness with a finite timeout,
// runs the HTTP/DB integration scenarios and (with --browser) a real Chromium
// flow, writes a sanitized evidence JSON, and always cleans up only what it
// created. It never stops an existing container, never mutates the caller
// environment and never logs a credential.
//
// Prerequisites:
//   cd core-api; ./gradlew bootJar
//   cd web; npm ci; npm run build
//
// Usage:
//   node scripts/verify-p1-11.mjs
//   node scripts/verify-p1-11.mjs --browser

import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, cpSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { runVerification } from './lib/verify-core.mjs';
import { runEntry } from './lib/entry.mjs';
import { buildConfig, createDocker, createPortProbe, createRequest, startChild } from './lib/adapters.mjs';
import { startErpFixture } from './lib/erp-fixture.mjs';
import { runP111Scenarios, assertBrowserFixtureTuples } from './lib/p1-11-scenarios.mjs';
import { runBrowserChecks } from './browser/p1-11-browser.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');

function parseFlag(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  if (index >= 0 && process.argv[index + 1]) return Number(process.argv[index + 1]);
  return fallback;
}

const ports = {
  pg: parseFlag('pg-port', 55433),
  mock: parseFlag('mock-port', 55382),
  erp: parseFlag('erp-port', 55381),
  core: parseFlag('core-port', 55380),
  web: parseFlag('web-port', 55300),
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

// Generated test-only secret: never the caller's environment, never written to
// the evidence file or logs.
const webhookSecret = `p111-${randomUUID()}`;
const withBrowser = process.argv.includes('--browser');
const config = buildConfig({ ports, repo, pgPassword: randomUUID(), withErp: true, webhookSecret, relayInterval: '1s' });

const evidenceDir = join(repo, 'output', 'p1-11');
mkdirSync(evidenceDir, { recursive: true });

const startChildDispatch = async (spec) => {
  if (spec.inProcess === 'erp') {
    return startErpFixture(spec);
  }
  return startChild(spec);
};

const verify = async (context) => {
  const { evidence } = await runP111Scenarios(context);
  if (withBrowser) {
    await runBrowserChecks({ ...context, fixtures: evidence.fixtures });
    await assertBrowserFixtureTuples({
      containers: context.containers,
      docker: context.docker,
      fixtures: evidence.fixtures,
      log: context.log,
    });
  }
  const summary = {
    generatedAt: new Date().toISOString(),
    corePort: ports.core,
    scenarios: evidence.steps,
    fixtures: evidence.fixtures,
  };
  const evidencePath = join(evidenceDir, 'evidence.json');
  writeFileSync(evidencePath, JSON.stringify(summary, null, 2), 'utf8');
  context.log(`INFO evidence ${evidencePath}`);
  return {};
};

const outcome = await runEntry({
  run: () =>
    runVerification({
      config,
      isPortOpen: createPortProbe(),
      docker: createDocker(),
      startChild: startChildDispatch,
      request: createRequest(),
      now: Date.now,
      sleep: (ms) => new Promise((done) => setTimeout(done, ms)),
      log: (message) => console.log(message),
      verify,
    }),
});

process.exitCode = outcome.exitCode;
