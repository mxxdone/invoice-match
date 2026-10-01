// P1-11 clean Compose smoke entry.
//
// It brings up the five-service Compose stack as a new, uniquely named project
// on freshly chosen isolated host ports, bound explicitly to 127.0.0.1 through a
// generated override file, with generated test-only credentials supplied only
// through a per-run env file. It verifies health and one end-to-end workflow
// over HTTP against the containerised stack (including the real signed
// auto-webhook path), then removes only that project's resources.
//
// Safety: it never runs a bare `docker compose down`, never prunes broadly,
// never touches an existing project/container/volume, never passes caller
// POSTGRES_*/COMPOSE_* values into the child, never mutates the caller
// environment and never logs a credential. A cleanup failure (down, unclosed
// child, failed file delete) fails the process even when the workflow passed.
//
// Usage:
//   node scripts/compose-smoke-p1-11.mjs

import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import {
  assertConfigPublishesOnLoopback,
  buildOverrideYaml,
  createRunCapture,
  runComposeSmoke,
  runStreaming,
  sanitizeComposeEnv,
  validateDistinctPorts,
} from './lib/compose-core.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');
const project = `im-p111-${randomUUID().slice(0, 8)}`;
const envPath = join(repo, 'output', 'p1-11', `compose-smoke-${project}.env`);
const overridePath = join(repo, 'output', 'p1-11', `compose-override-${project}.yml`);
mkdirSync(dirname(envPath), { recursive: true });

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolvePort(port));
    });
  });
}

const ports = {
  POSTGRES_PORT: await freePort(),
  CORE_API_PORT: await freePort(),
  WEB_PORT: await freePort(),
  MOCK_ERP_PORT: await freePort(),
  MOCK_PURCHASING_PORT: await freePort(),
};
validateDistinctPorts(ports);

const generatedEnv = {
  POSTGRES_PASSWORD: randomUUID(),
  MOCK_ERP_WEBHOOK_SECRET: randomUUID(),
  ...ports,
};

const composeArgs = ['compose', '-p', project, '--env-file', envPath, '-f', 'compose.yaml', '-f', overridePath];
// The generated env file is the only source for the generated keys; the child
// never inherits a caller POSTGRES_*/COMPOSE_* value.
const childEnv = sanitizeComposeEnv(process.env);
const compose = (args, timeoutMs) =>
  runStreaming('docker', [...composeArgs, ...args], { timeoutMs, env: childEnv, cwd: repo });
const runCapture = createRunCapture();
const configCheck = async () => {
  const { code, stdout } = await runCapture('docker', [...composeArgs, 'config', '--format', 'json'], {
    timeoutMs: 60000,
    env: childEnv,
    cwd: repo,
  });
  if (code !== 0) {
    throw new Error(`docker compose config --format json exited ${code}`);
  }
  assertConfigPublishesOnLoopback(stdout);
};

async function request(url, init = {}, timeoutMs = 10000) {
  const response = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
  const body = await response.text();
  return { status: response.status, ok: response.ok, body };
}

function basic(user, pass) {
  return 'Basic ' + Buffer.from(`${user}:${pass}`, 'utf8').toString('base64');
}

async function waitFor(label, predicate, { timeoutMs = 60000, intervalMs = 1000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const value = await predicate();
      if (value) return value;
    } catch { /* retry */ }
    if (Date.now() >= deadline) throw new Error(`timed out waiting for ${label}`);
    await new Promise((resolveSleep) => setTimeout(resolveSleep, intervalMs));
  }
}

async function verify({ request: call, generatedEnv: env, log }) {
  const core = `http://127.0.0.1:${env.CORE_API_PORT}`;
  const web = `http://127.0.0.1:${env.WEB_PORT}`;
  const erp = `http://127.0.0.1:${env.MOCK_ERP_PORT}`;
  const purchasing = `http://127.0.0.1:${env.MOCK_PURCHASING_PORT}`;

  const healthChecks = [
    ['core actuator', `${core}/actuator/health`, '"status":"UP"'],
    ['web health', `${web}/api/health`, ''],
    ['mock-erp health', `${erp}/health`, '"status":"UP"'],
    ['mock-purchasing health', `${purchasing}/health`, '"status":"UP"'],
    ['mock-purchasing aggregate', `${purchasing}/api/purchase-orders/PO-1001`, '"purchaseOrderId":"PO-1001"'],
  ];
  for (const [name, url, marker] of healthChecks) {
    const response = await call(url);
    if (!response.ok || (marker && !response.body.includes(marker))) {
      throw new Error(`${name} was not healthy (status ${response.status})`);
    }
    log(`PASS compose health ${name}`);
  }
  const anon = await call(`${core}/api/me`);
  if (anon.status !== 401) throw new Error(`compose core /api/me expected 401 (was ${anon.status})`);
  log('PASS compose core fails closed for anonymous access');

  const submitter = { authorization: basic('submitter', 'submitter-pass') };
  const approver = { authorization: basic('approver', 'approver-pass') };
  const operator = { authorization: basic('operator', 'operator-pass') };
  const json = (actor) => ({ ...actor, 'content-type': 'application/json' });
  const invoice = `INV-COMPOSE-${Date.now()}`;
  const command = async (method, path, actor, body) => {
    const response = await call(`${core}${path}`, {
      method,
      headers: json(actor),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`${method} ${path} failed (${response.status})`);
    return response.body ? JSON.parse(response.body) : null;
  };

  const createdCase = await command('POST', '/api/invoice-cases', submitter, { requestId: `c-${invoice}`, supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber: invoice });
  const draft = await command('PUT', `/api/invoice-cases/${createdCase.id}/draft`, submitter, { requestId: `d-${invoice}`, expectedCaseVersion: createdCase.version, lines: [{ lineNumber: 1, rawItemName: 'Copy Paper', quantity: 5, unitPrice: 2500, confirmedItemId: 'ITEM-A4-80' }] });
  await command('POST', `/api/invoice-cases/${createdCase.id}/submit`, submitter, { requestId: `s-${invoice}`, expectedCaseVersion: draft.version });
  await command('POST', `/api/invoice-cases/${createdCase.id}/match`, operator, { requestId: `m-${invoice}` });
  const caseDetail = await call(`${core}/api/invoice-cases/${createdCase.id}`, { headers: approver });
  const caseVersion = JSON.parse(caseDetail.body).version;
  const snapshot = await command('POST', `/api/invoice-cases/${createdCase.id}/review-snapshots`, approver, { requestId: `f-${invoice}`, expectedCaseVersion: caseVersion });
  const approval = await command('POST', `/api/invoice-cases/${createdCase.id}/approve`, approver, {
    requestId: `a-${invoice}`,
    expectedCaseVersion: caseVersion,
    reviewSnapshotId: snapshot.id,
    reviewPayloadHash: snapshot.payloadHash,
  });
  if (approval.amount !== 5 * 2500) throw new Error(`compose approval amount expected 12500 (was ${approval.amount})`);
  log('PASS compose workflow approved');

  const handoff = await waitFor('compose handoff EXPORTED', async () => {
    const response = await call(`${core}/api/invoice-cases/${createdCase.id}/handoff`, { headers: approver });
    const body = JSON.parse(response.body);
    return body.caseStatus === 'EXPORTED' ? body : null;
  });
  if (handoff.payment?.paymentStatus !== 'ACKNOWLEDGED' || handoff.payment?.outboxStatus !== 'DELIVERED') {
    throw new Error('compose handoff did not reach ACKNOWLEDGED/DELIVERED');
  }
  log('PASS compose handoff ACKNOWLEDGED/DELIVERED/EXPORTED (ERP acceptance, not a fund transfer)');

  const externalKey = `${approval.paymentRequestId}:${handoff.payment.exportVersion}`;
  const inquiry = await call(`${erp}/api/payment-exports/${encodeURIComponent(externalKey)}`);
  if (inquiry.status !== 200) throw new Error(`compose mock-erp inquiry failed (${inquiry.status})`);
  const inquiryBody = JSON.parse(inquiry.body);
  if (inquiryBody.externalPaymentKey !== externalKey || inquiryBody.outcome !== 'ACKNOWLEDGED') {
    throw new Error('compose mock-erp inquiry did not converge on the same key/outcome');
  }
  log('PASS compose mock-erp holds one logical record for the export key');
}

const outcome = await runComposeSmoke({
  project,
  envPath,
  overridePath,
  generatedEnv,
  overrideYaml: buildOverrideYaml(),
  compose,
  request,
  writeExclusive: (path, contents) => writeFileSync(path, contents, { encoding: 'utf8', mode: 0o600, flag: 'wx' }),
  removeFile: (path) => rmSync(path),
  verify,
  configCheck,
  log: (message) => console.log(message),
  error: (message) => console.error(message),
});

if (outcome.ok) {
  console.log('PASS compose smoke');
} else {
  for (const message of outcome.errors) {
    console.error(`COMPOSE SMOKE FAILED: ${message}`);
  }
}
process.exitCode = outcome.exitCode;
