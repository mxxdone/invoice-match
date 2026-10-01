// P1-11 clean Compose smoke.
//
// It brings up the five-service Compose stack as a *new, uniquely named*
// project on freshly chosen isolated host ports with generated test-only
// credentials, verifies health and one end-to-end workflow over HTTP against
// the containerised stack (including the real signed auto-webhook path), and
// then removes only that project's resources. It never runs a bare
// `docker compose down`, never prunes broadly, never touches an existing
// project/container/volume and never logs a credential.
//
// Usage:
//   node scripts/compose-smoke-p1-11.mjs

import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');
const project = `im-p111-${randomUUID().slice(0, 8)}`;
const envFile = join(repo, 'output', 'p1-11', 'compose-smoke.env');

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

function runStreaming(command, args, { timeoutMs, cwd = repo } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, args, { cwd, windowsHide: true, shell: false, env: process.env });
    const timer = setTimeout(() => {
      try { child.kill('SIGKILL'); } catch { /* best effort */ }
      rejectPromise(new Error(`timed out after ${timeoutMs}ms: ${command} ${args.join(' ')}`));
    }, timeoutMs);
    child.stdout.on('data', (data) => process.stdout.write(data));
    child.stderr.on('data', (data) => process.stderr.write(data));
    child.once('error', (error) => { clearTimeout(timer); rejectPromise(error); });
    child.once('close', (code) => { clearTimeout(timer); resolvePromise(code ?? 1); });
  });
}

function compose(args, timeoutMs) {
  return runStreaming('docker', ['compose', '-p', project, '--env-file', envFile, '-f', 'compose.yaml', ...args], { timeoutMs });
}

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

let created = false;
let failure = null;

try {
  if (!existsSync(join(repo, 'compose.yaml'))) {
    throw new Error('compose.yaml is missing');
  }
  const ports = {
    POSTGRES_PORT: await freePort(),
    CORE_API_PORT: await freePort(),
    WEB_PORT: await freePort(),
    MOCK_ERP_PORT: await freePort(),
    MOCK_PURCHASING_PORT: await freePort(),
  };
  mkdirSync(dirname(envFile), { recursive: true });
  const lines = [
    `POSTGRES_PASSWORD=${randomUUID()}`,
    `MOCK_ERP_WEBHOOK_SECRET=${randomUUID()}`,
    ...Object.entries(ports).map(([key, value]) => `${key}=${value}`),
    '',
  ];
  writeFileSync(envFile, lines.join('\n'), { encoding: 'utf8', mode: 0o600 });
  console.log(`INFO compose project ${project}`);
  console.log(`INFO compose ports core=${ports.CORE_API_PORT} web=${ports.WEB_PORT} erp=${ports.MOCK_ERP_PORT} purchasing=${ports.MOCK_PURCHASING_PORT} pg=${ports.POSTGRES_PORT}`);

  // `up --build --wait` exits only when every healthcheck is healthy.
  const up = await compose(['up', '--build', '-d', '--wait'], 1500000);
  if (up !== 0) {
    throw new Error(`docker compose up failed with exit ${up}`);
  }
  created = true;
  console.log('PASS compose up --build --wait');

  const core = `http://127.0.0.1:${ports.CORE_API_PORT}`;
  const web = `http://127.0.0.1:${ports.WEB_PORT}`;
  const erp = `http://127.0.0.1:${ports.MOCK_ERP_PORT}`;
  const purchasing = `http://127.0.0.1:${ports.MOCK_PURCHASING_PORT}`;

  const healthChecks = [
    ['core actuator', `${core}/actuator/health`, '"status":"UP"'],
    ['web health', `${web}/api/health`, ''],
    ['mock-erp health', `${erp}/health`, '"status":"UP"'],
    ['mock-purchasing health', `${purchasing}/health`, '"status":"UP"'],
    ['mock-purchasing aggregate', `${purchasing}/api/purchase-orders/PO-1001`, '"purchaseOrderId":"PO-1001"'],
  ];
  for (const [name, url, marker] of healthChecks) {
    const response = await request(url);
    if (!response.ok || (marker && !response.body.includes(marker))) {
      throw new Error(`${name} was not healthy (status ${response.status})`);
    }
    console.log(`PASS compose health ${name}`);
  }
  const anon = await request(`${core}/api/me`);
  if (anon.status !== 401) throw new Error(`compose core /api/me expected 401 (was ${anon.status})`);
  console.log('PASS compose core fails closed for anonymous access');

  // End-to-end workflow over the containerised stack, including the real
  // mock-erp signed auto-webhook.
  const submitter = { authorization: basic('submitter', 'submitter-pass') };
  const approver = { authorization: basic('approver', 'approver-pass') };
  const operator = { authorization: basic('operator', 'operator-pass') };
  const json = (actor) => ({ ...actor, 'content-type': 'application/json' });
  const invoice = `INV-COMPOSE-${Date.now()}`;
  const call = async (method, path, actor, body) => {
    const response = await request(`${core}${path}`, {
      method,
      headers: json(actor),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`${method} ${path} failed (${response.status})`);
    return response.body ? JSON.parse(response.body) : null;
  };

  const createdCase = await call('POST', '/api/invoice-cases', submitter, { requestId: `c-${invoice}`, supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber: invoice });
  const draft = await call('PUT', `/api/invoice-cases/${createdCase.id}/draft`, submitter, { requestId: `d-${invoice}`, expectedCaseVersion: createdCase.version, lines: [{ lineNumber: 1, rawItemName: 'Copy Paper', quantity: 5, unitPrice: 2500, confirmedItemId: 'ITEM-A4-80' }] });
  await call('POST', `/api/invoice-cases/${createdCase.id}/submit`, submitter, { requestId: `s-${invoice}`, expectedCaseVersion: draft.version });
  await call('POST', `/api/invoice-cases/${createdCase.id}/match`, operator, { requestId: `m-${invoice}` });
  const caseDetail = await request(`${core}/api/invoice-cases/${createdCase.id}`, { headers: approver });
  const caseVersion = JSON.parse(caseDetail.body).version;
  const snapshot = await call('POST', `/api/invoice-cases/${createdCase.id}/review-snapshots`, approver, { requestId: `f-${invoice}`, expectedCaseVersion: caseVersion });
  const approval = await call('POST', `/api/invoice-cases/${createdCase.id}/approve`, approver, {
    requestId: `a-${invoice}`,
    expectedCaseVersion: caseVersion,
    reviewSnapshotId: snapshot.id,
    reviewPayloadHash: snapshot.payloadHash,
  });
  console.log('PASS compose workflow approved');

  const handoff = await waitFor('compose handoff EXPORTED', async () => {
    const response = await request(`${core}/api/invoice-cases/${createdCase.id}/handoff`, { headers: approver });
    const body = JSON.parse(response.body);
    return body.caseStatus === 'EXPORTED' ? body : null;
  });
  if (handoff.payment?.paymentStatus !== 'ACKNOWLEDGED' || handoff.payment?.outboxStatus !== 'DELIVERED') {
    throw new Error('compose handoff did not reach ACKNOWLEDGED/DELIVERED');
  }
  console.log('PASS compose handoff ACKNOWLEDGED/DELIVERED/EXPORTED (ERP acceptance, not a fund transfer)');

  const externalKey = `${approval.paymentRequestId}:${handoff.payment.exportVersion}`;
  const inquiry = await request(`${erp}/api/payment-exports/${encodeURIComponent(externalKey)}`);
  if (inquiry.status !== 200) throw new Error(`compose mock-erp inquiry failed (${inquiry.status})`);
  const inquiryBody = JSON.parse(inquiry.body);
  if (inquiryBody.externalPaymentKey !== externalKey || inquiryBody.outcome !== 'ACKNOWLEDGED') {
    throw new Error('compose mock-erp inquiry did not converge on the same key/outcome');
  }
  console.log('PASS compose mock-erp holds one logical record for the export key');

  console.log('PASS compose smoke');
} catch (error) {
  failure = error;
} finally {
  if (failure) {
    try {
      await compose(['logs', '--no-color'], 120000);
    } catch (logError) {
      console.error(`WARN compose logs unavailable: ${logError.message}`);
    }
  }
  // Always scope cleanup to this uniquely named project. `down` on a project
  // that never started is a no-op, and it can never touch a user's stack.
  try {
    const down = await compose(['down', '-v', '--remove-orphans'], 300000);
    if (down !== 0) {
      console.error(`WARN compose down exited ${down}`);
    } else if (created) {
      console.log('INFO compose project owned resources removed');
    }
  } catch (downError) {
    console.error(`WARN compose down failed: ${downError.message}`);
  }
  try {
    rmSync(envFile, { force: true });
  } catch { /* best effort */ }
}

// Never leave the generated credential file behind.
if (failure) {
  console.error(`COMPOSE SMOKE FAILED: ${failure.message}`);
  process.exitCode = 1;
}
