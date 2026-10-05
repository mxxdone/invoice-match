// P4-06 real-browser acceptance CLI.
//
// Starts only throwaway resources it creates (PostgreSQL with pgvector, RabbitMQ,
// mock-purchasing, the latest built core-api jar, the built web standalone, and
// the installed Linux ai-worker image with the actual LangGraph graph fixture),
// records and owns their container ids, waits with finite timeouts, seeds only
// the parser/match inputs over real HTTP, then drives a real Chromium through the
// graph human-review flow, the explicit successor flow, the role read surface and
// the AI-off freeze/approval flow. It writes screenshots plus a summary under
// output/playwright/p4-06, and always cleans up only what it created. It never
// stops an existing container, never changes the caller's environment and never
// logs a credential.
//
// Prerequisites:
//   cd core-api; ./gradlew bootJar
//   cd web; npm ci; npm run build
//
// Usage:
//   node scripts/verify-p4-06.mjs

import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, cpSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:net';
import { createDocker } from './lib/adapters.mjs';
import { runP406BrowserChecks } from './lib/p4-06-browser.mjs';
import { VerifyError } from './lib/verify-core.mjs';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const prefix = 'im-p406-' + randomUUID().slice(0, 8);
const output = join(repo, 'output', 'p4-06', prefix);
mkdirSync(output, { recursive: true });
const docker = createDocker({ timeoutMs: 60000 });
const network = prefix + '-network';
const owned = [];
let networkCreated = false;
const resources = () => writeFileSync(join(output, 'resources.json'), JSON.stringify({ network, owned, ports }, null, 2));
const pgPassword = randomUUID();
const rabbitUser = 'graph-test';
const rabbitPassword = 'graph-test-secret';
const storageUser = 'p406-' + randomUUID().slice(0, 8);
const storagePassword = randomUUID();
const workerToken = randomUUID() + randomUUID();
const steps = [];
const record = (label) => { steps.push(label); console.log('PASS ' + label); };
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

// Frozen thread budget shared by the graph contract (Plan Phase 4 common
// contract): one thread spends at most 5 calls and 40,000 tokens across its
// start and resume segments. A resume or redelivery must not reset it, so the
// harness asserts the accumulated reservation stays inside these exact bounds.
const GRAPH_THREAD_BUDGET = Object.freeze({ maxCalls: 5, maxTokens: 40000 });

const IMAGE = {
  pg: process.env.PG_IMAGE ?? 'pgvector/pgvector:0.8.6-pg18-bookworm',
  rabbit: 'rabbitmq:4.2-alpine',
  node: process.env.NODE_RUNTIME_IMAGE ?? 'node:24-alpine',
  jre: process.env.JRE_RUNTIME_IMAGE ?? 'eclipse-temurin:21-jre',
  worker: process.env.WORKER_RUNTIME_IMAGE ?? 'invoice-match-p4-06-verification:latest',
  minio: process.env.MINIO_RUNTIME_IMAGE ?? 'invoice-match-minio:p2-security-2025-10-15',
};

async function command(args) {
  const result = await docker.run(args);
  if (result.code !== 0) throw Error(`Docker operation failed: ${args[0]} ${(result.stderr || '').trim()}`);
  return result.stdout.trim();
}
function port() {
  return new Promise((done, reject) => {
    const socket = createServer();
    socket.on('error', reject).listen(0, '127.0.0.1', () => {
      const value = socket.address().port;
      socket.close(() => done(value));
    });
  });
}
async function container(suffix, image, args = [], env = {}, commandArgs = []) {
  const name = prefix + '-' + suffix;
  const options = ['run', '-d', '--name', name, '--network', network, '--network-alias', suffix];
  for (const [key, value] of Object.entries(env)) options.push('-e', key + '=' + value);
  const id = await command([...options, ...args, image, ...commandArgs]);
  owned.push(id); resources();
  console.log(`INFO owned ${suffix} ${id.slice(0, 12)}`);
  return id;
}
async function wait(label, predicate, timeoutMs = 120000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    // A predicate failure is a real failure (for example an owned service that
    // exited permanently); it must abort immediately instead of being retried
    // until the timeout. Readiness predicates return false for "not yet ready".
    const value = await predicate();
    if (value) return value;
    last = value;
    await sleep(500);
  }
  throw new VerifyError(`Timed out: ${label} (last=${last})`);
}
async function running(id) { return (await command(['inspect', '--format', '{{.State.Running}}', id])) === 'true'; }
async function exitCode(id) { return Number(await command(['inspect', '--format', '{{.State.ExitCode}}', id])); }
async function containerIp(id) { return command(['inspect', '--format', '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', id]); }
async function ready(url, id, timeoutMs = 240000) {
  await wait(url, async () => {
    if (!(await running(id))) throw new VerifyError(`owned service exited: ${id.slice(0, 12)}`);
    try { return (await fetch(url, { signal: AbortSignal.timeout(2500) })).ok; } catch { return false; }
  }, timeoutMs);
}
async function sql(statement) {
  const result = await docker.exec(pg, ['psql', '-U', 'invoice_match', '-d', 'invoice_match', '-v', 'ON_ERROR_STOP=1', '-tAc', statement]);
  if (result.code !== 0) throw new VerifyError(`Database observation failed: ${(result.stderr || '').trim()}`);
  return result.stdout.trim();
}
async function rabbitCommand(args) { return docker.run(['exec', '--user', 'rabbitmq', rabbit, ...args]); }

const ports = { pg: await port(), rabbit: await port(), minio: await port(), core: await port(), web: await port(), coreOff: await port(), webOff: await port() };
const coreUrl = `http://127.0.0.1:${ports.core}`;
const coreOffUrl = `http://127.0.0.1:${ports.coreOff}`;

function auth(actor) { return 'Basic ' + Buffer.from(actor + ':' + actor + '-pass').toString('base64'); }
async function api(base, method, path, body, actor = 'submitter') {
  const response = await fetch(base + path, {
    method, signal: AbortSignal.timeout(20000),
    headers: { 'content-type': 'application/json', authorization: auth(actor) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let value = null;
  try { value = text ? JSON.parse(text) : null; } catch { value = { raw: text }; }
  if (!response.ok) throw new VerifyError(`API ${method} ${path} [${actor}] -> ${response.status} ${value && (value.code || value.message) || ''}`);
  return value;
}

const demoUsers = JSON.stringify({
  security: { demo: { users: [
    { username: 'submitter', password: '{bcrypt}$2a$10$dy82eO2.Xqf1r/xnYb0d.umTy.YPhylxIHoWLR5gi.conT.c3lpHm', roles: ['SUBMITTER'] },
    { username: 'approver', password: '{bcrypt}$2a$10$zI98Q/Kc88bhkspHb/BRneFPm1bxu5d4ciVHaNBm/nUFEy9FYKE/O', roles: ['APPROVER'] },
    { username: 'operator', password: '{bcrypt}$2a$10$43jqVAOkzegqkQVkGJi7luHB.MK5GGe1uexfRnH9C07fWEzVdcsfq', roles: ['OPERATOR'] },
  ] } },
});

function coreEnv(graphEnabled) {
  const env = {
    DB_URL: 'jdbc:postgresql://postgres:5432/invoice_match',
    DB_USER: 'invoice_match',
    DB_PASSWORD: pgPassword,
    SPRING_PROFILES_ACTIVE: 'local',
    PURCHASING_BASE_URL: 'http://purchasing:8082',
    SPRING_APPLICATION_JSON: demoUsers,
    ANALYSIS_REQUEST_ENABLED: 'true',
    ANALYSIS_RELAY_ENABLED: 'true',
    ANALYSIS_RELAY_INTERVAL: '1s',
    ANALYSIS_RABBIT_HOST: 'rabbit',
    ANALYSIS_RABBIT_PORT: '5672',
    ANALYSIS_RABBIT_USERNAME: rabbitUser,
    ANALYSIS_RABBIT_PASSWORD: rabbitPassword,
    ANALYSIS_WORKER_ENABLED: 'true',
    ANALYSIS_WORKER_TOKEN: workerToken,
    ANALYSIS_AI_ENABLED: 'false',
    LOGGING_LEVEL_COM_INVOICEMATCH_CORE_ANALYSIS: 'DEBUG',
    DOCUMENT_STORAGE_ENABLED: 'true',
    DOCUMENT_STORAGE_ENDPOINT: 'http://minio:9000',
    DOCUMENT_STORAGE_PUBLIC_ENDPOINT: `http://127.0.0.1:${ports.minio}`,
    DOCUMENT_STORAGE_ACCESS_KEY: storageUser,
    DOCUMENT_STORAGE_SECRET_KEY: storagePassword,
  };
  if (graphEnabled) {
    Object.assign(env, {
      ANALYSIS_GRAPH_ENABLED: 'true',
      ANALYSIS_GRAPH_COST_CEILING: '1',
      ANALYSIS_GRAPH_LEASE_DURATION: '120s',
      ANALYSIS_GRAPH_RELAY_ENABLED: 'true',
      ANALYSIS_GRAPH_RELAY_INTERVAL: '1s',
      ANALYSIS_GRAPH_RELAY_RABBIT_HOST: 'rabbit',
      ANALYSIS_GRAPH_RELAY_RABBIT_PORT: '5672',
      ANALYSIS_GRAPH_RELAY_RABBIT_USERNAME: rabbitUser,
      ANALYSIS_GRAPH_RELAY_RABBIT_PASSWORD: rabbitPassword,
    });
  }
  return env;
}

let pg, rabbit, rabbitIp, minio, purchasing, core, web, coreOff, webOff, parserWorker, graphStart;

function samplePdf() {
  const pdfPath = join(output, 'sample.pdf');
  if (!existsSync(pdfPath)) {
    const code = 'import sys,pathlib;sys.path.insert(0,"ai-worker/tests");from fixtures import build_pdf;p=pathlib.Path(sys.argv[1]);p.write_bytes(build_pdf(["Premium Copy Paper A4 7 2500","Laser Toner Black 9 55000"]))';
    const result = spawnSync(process.env.PYTHON_BIN ?? 'python', ['-c', code, pdfPath], { cwd: repo, encoding: 'utf8' });
    if (result.status !== 0) throw new VerifyError('sample.pdf generation failed: ' + (result.stderr || result.stdout || ''));
  }
  return readFileSync(pdfPath);
}

async function seedCase(base, invoiceNumber, itemId) {
  const created = await api(base, 'POST', '/api/invoice-cases', { requestId: randomUUID(), supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber });
  const draft = await api(base, 'PUT', `/api/invoice-cases/${created.id}/draft`, {
    requestId: randomUUID(), expectedCaseVersion: created.version,
    lines: [{ lineNumber: 1, rawItemName: 'Copy Paper', quantity: 5, unitPrice: 2500, confirmedItemId: itemId }],
  });
  let version = draft.version;
  const bytes = samplePdf();
  const checksum = createHash('sha256').update(bytes).digest('hex');
  const revision = await sql(`select current_draft_revision_id from invoice_case where id='${created.id}'`);
  const reserved = await api(base, 'POST', `/api/invoice-cases/${created.id}/documents/presign`, {
    requestId: randomUUID(), expectedCaseVersion: version, draftRevisionId: revision,
    fileName: 'sample.pdf', mediaType: 'application/pdf', sizeBytes: bytes.length, checksum,
  });
  const uploaded = await fetch(reserved.uploadUrl, { method: 'PUT', headers: reserved.requiredHeaders, body: bytes, signal: AbortSignal.timeout(15000) });
  if (!uploaded.ok) throw new VerifyError(`presigned upload failed: ${uploaded.status}`);
  const completed = await api(base, 'POST', `/api/invoice-cases/${created.id}/documents/complete`, {
    requestId: randomUUID(), expectedCaseVersion: version, draftRevisionId: revision, documentId: reserved.documentId, checksum,
  });
  version = completed.caseVersion;
  const submitted = await api(base, 'POST', `/api/invoice-cases/${created.id}/submit`, { requestId: randomUUID(), expectedCaseVersion: version });
  await wait('analysis run completed for ' + invoiceNumber, async () => {
    const status = await sql(`select status from analysis_run where invoice_case_id='${created.id}' order by input_version desc limit 1`);
    if (status === 'FAILED' || status === 'DEAD_LETTERED') throw new VerifyError('analysis run ' + status + ' for ' + invoiceNumber);
    return status === 'COMPLETED';
  }, 180000);
  await api(base, 'POST', `/api/invoice-cases/${created.id}/match`, { requestId: randomUUID() }, 'operator');
  return { id: created.id, invoiceNumber, caseVersion: submitted.version };
}

async function startGraphWorker(suffix, mode, segment, target, { addHost = null, deadlineSeconds = 45 } = {}) {
  const dummyRun = randomUUID();
  const dummyHash = createHash('sha256').update(suffix).digest('hex');
  const options = ['--init', '--memory', '1g', '--pids-limit', '256',
    '-v', join(repo, 'scripts', 'lib', 'p4-06-graph-fixture.py') + ':/scripts/fixture.py:ro'];
  // host.docker.internal resolves to the broker's own container IP, so the
  // test-only fixture (which hardcodes that host name) needs no host publish.
  if (addHost) options.push('--add-host', `host.docker.internal:${addHost}`);
  return container(suffix, IMAGE.worker, options,
    { GRAPH_FIXTURE_WORKER_TOKEN: workerToken, P406_RABBIT_HOST: 'host.docker.internal',
      P406_RABBIT_USER: rabbitUser, P406_RABBIT_PASSWORD: rabbitPassword, P406_FIXTURE_DEADLINE: String(deadlineSeconds) },
    ['python', '/scripts/fixture.py', mode, 'http://core:8080', dummyRun, dummyHash, '5672', segment, String(target)]);
}

try {
  assert(existsSync(join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar')), 'Build the latest bootJar first');
  const webStandalone = join(repo, 'web', '.next', 'standalone');
  assert(existsSync(join(webStandalone, 'server.js')), 'Build the web standalone first');
  if (!existsSync(join(webStandalone, '.next', 'static'))) {
    mkdirSync(join(webStandalone, '.next'), { recursive: true });
    cpSync(join(repo, 'web', '.next', 'static'), join(webStandalone, '.next', 'static'), { recursive: true });
  }

  await command(['network', 'create', network]); networkCreated = true; resources();

  pg = await container('postgres', IMAGE.pg, ['--tmpfs', '/var/lib/postgresql:rw,size=512m'],
    { POSTGRES_DB: 'invoice_match', POSTGRES_USER: 'invoice_match', POSTGRES_PASSWORD: pgPassword });
  await wait('postgres', async () => (await docker.exec(pg, ['pg_isready', '-h', '127.0.0.1', '-U', 'invoice_match'])).code === 0);

  // No host publish: the graph worker reaches the broker over the isolated
  // network through host.docker.internal mapped to the broker's own IP, so the
  // broker is never bound to a host interface.
  rabbit = await container('rabbit', IMAGE.rabbit, ['--memory', '640m'],
    { RABBITMQ_DEFAULT_USER: rabbitUser, RABBITMQ_DEFAULT_PASS: rabbitPassword });
  await wait('rabbit', async () => {
    if (!(await running(rabbit))) throw new VerifyError('RabbitMQ exited');
    return (await rabbitCommand(['rabbitmq-diagnostics', '-q', 'check_running'])).code === 0
      && (await rabbitCommand(['rabbitmq-diagnostics', '-q', 'check_port_connectivity'])).code === 0;
  });
  rabbitIp = await containerIp(rabbit);

  minio = await container('minio', IMAGE.minio,
    ['-p', `127.0.0.1:${ports.minio}:9000`, '--tmpfs', '/data:rw,size=256m,uid=10001,gid=10001'],
    { MINIO_ROOT_USER: storageUser, MINIO_ROOT_PASSWORD: storagePassword });
  await ready(`http://127.0.0.1:${ports.minio}/minio/health/ready`, minio, 180000);
  await wait('minio bucket', async () => (await docker.exec(minio, ['sh', '-ec',
    'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc mb --ignore-existing local/invoice-documents >/dev/null; mc anonymous set none local/invoice-documents >/dev/null'])).code === 0, 60000);

  purchasing = await container('purchasing', IMAGE.node,
    ['--entrypoint', 'node', '-v', join(repo, 'mock-purchasing', 'server.js') + ':/verify/server.cjs:ro'],
    { PORT: '8082' }, ['/verify/server.cjs']);
  await wait('purchasing', async () => {
    if (!(await running(purchasing))) throw new VerifyError('purchasing exited');
    return true;
  });

  core = await container('core', IMAGE.jre,
    ['--entrypoint', 'java', '--memory', '1200m', '-p', `127.0.0.1:${ports.core}:8080`,
      '-v', join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar') + ':/verify/current.jar:ro'],
    coreEnv(true), ['-Xmx512m', '-jar', '/verify/current.jar', '--server.address=0.0.0.0']);
  await ready(coreUrl + '/actuator/health', core, 300000);

  web = await container('web', IMAGE.node,
    ['-p', `127.0.0.1:${ports.web}:3000`, '-v', webStandalone + ':/app', '-w', '/app'],
    { CORE_API_URL: 'http://core:8080', PORT: '3000', HOSTNAME: '0.0.0.0' }, ['server.js']);
  await ready(`http://127.0.0.1:${ports.web}/login`, web, 180000);

  parserWorker = await container('parser-worker', IMAGE.worker, ['--memory', '1g', '--pids-limit', '256'],
    { CORE_API_URL: 'http://core:8080', ANALYSIS_WORKER_TOKEN: workerToken, ANALYSIS_RABBIT_HOST: 'rabbit',
      ANALYSIS_RABBIT_PORT: '5672', ANALYSIS_RABBIT_USERNAME: rabbitUser, ANALYSIS_RABBIT_PASSWORD: rabbitPassword },
    ['ai-worker', 'consume']);
  await wait('parser consumer ready', async () => {
    if (!(await running(parserWorker))) throw new VerifyError('parser worker exited');
    const result = await rabbitCommand(['rabbitmqctl', '-q', 'list_queues', 'name', 'consumers']);
    return result.code === 0 && /invoice\.analysis\.requests\s+1/.test(result.stdout);
  }, 120000);

  coreOff = await container('core-off', IMAGE.jre,
    ['--entrypoint', 'java', '--memory', '1024m', '-p', `127.0.0.1:${ports.coreOff}:8080`,
      '-v', join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar') + ':/verify/current.jar:ro'],
    coreEnv(false), ['-Xmx448m', '-jar', '/verify/current.jar', '--server.address=0.0.0.0']);
  await ready(coreOffUrl + '/actuator/health', coreOff, 300000);

  webOff = await container('web-off', IMAGE.node,
    ['-p', `127.0.0.1:${ports.webOff}:3000`, '-v', webStandalone + ':/app', '-w', '/app'],
    { CORE_API_URL: 'http://core-off:8080', PORT: '3000', HOSTNAME: '0.0.0.0' }, ['server.js']);
  await ready(`http://127.0.0.1:${ports.webOff}/login`, webOff, 180000);

  const graphCase = await seedCase(coreUrl, 'INV-P406-GRAPH-' + randomUUID().slice(0, 8), 'ITEM-A4-80');
  const offCase = await seedCase(coreOffUrl, 'INV-P406-OFF-' + randomUUID().slice(0, 8), 'ITEM-A4-80');
  record('isolated PostgreSQL/RabbitMQ/purchasing/Core/Web and seeded graph + AI-off cases');

  const evidence = {};
  const onBeforeStep = async (step) => {
    if (step === 'reserve') {
      graphStart = await startGraphWorker('graph-start', 'broker-start', 'start', 1, { addHost: rabbitIp });
    } else if (step === 'verify-completed') {
      evidence.graphRunId = await sql(`select id from graph_run where invoice_case_id='${graphCase.id}' order by created_at asc limit 1`);
      console.log('INFO graph run before resume ' + evidence.graphRunId);
      const killId = await startGraphWorker('graph-kill', 'kill-resume', 'resume', 1, { addHost: rabbitIp });
      await wait('kill-resume worker to exit', async () => !(await running(killId)), 90000);
      evidence.killExitCode = await exitCode(killId);
      if (evidence.killExitCode !== 137) throw new VerifyError(`kill-resume worker did not die by SIGKILL (exit=${evidence.killExitCode})`);
      // The killed worker's lease is still active, so the real redelivery is
      // BUSY/deferred until the lease expires; this consumer stays alive across
      // that recovery window and completes the run exactly once.
      const resumeId = await startGraphWorker('graph-resume', 'broker-resume', 'resume', 2, { addHost: rabbitIp, deadlineSeconds: 300 });
      evidence.resumeWorkerId = resumeId.slice(0, 12);
      await wait('graph resume completed', async () => (await sql(`select status from graph_run where id='${evidence.graphRunId}'`)) === 'COMPLETED', 300000);
      evidence.resumeConsumption = await sql(`select count(*) from graph_resume_consumption where run_id='${evidence.graphRunId}'`);
      evidence.resumeAttempts = await sql(`select resume_attempts from graph_run where id='${evidence.graphRunId}'`);
      evidence.startAttempts = await sql(`select start_attempts from graph_run where id='${evidence.graphRunId}'`);
      evidence.reservedCalls = await sql(`select reserved_calls from graph_run where id='${evidence.graphRunId}'`);
      evidence.reservedTokens = await sql(`select reserved_tokens from graph_run where id='${evidence.graphRunId}'`);
      evidence.graphPayloadHash = await sql(`select payload_hash from graph_proposal where run_id='${evidence.graphRunId}'`);
      evidence.graphContextHash = await sql(`select context_hash from graph_run where id='${evidence.graphRunId}'`);
      if (evidence.resumeConsumption !== '1') throw new VerifyError(`expected one resume consumption, got ${evidence.resumeConsumption}`);
      // Exact attempt/kill evidence: the start segment ran once, the killed
      // resume attempt and the redelivered resume attempt make two, the worker
      // really died by SIGKILL, and exactly one resume was consumed.
      if (evidence.killExitCode !== 137) throw new VerifyError(`expected the resume worker to die by SIGKILL (137), got ${evidence.killExitCode}`);
      if (evidence.startAttempts !== '1') throw new VerifyError(`expected exactly one start attempt, got ${evidence.startAttempts}`);
      if (evidence.resumeAttempts !== '2') throw new VerifyError(`expected exactly two resume attempts, got ${evidence.resumeAttempts}`);
      // The killed + redelivered resume must keep spending the same frozen
      // thread budget instead of starting a fresh one.
      const reservedCalls = Number(evidence.reservedCalls);
      const reservedTokens = Number(evidence.reservedTokens);
      if (reservedCalls !== 2) throw new VerifyError(`expected the fixture's two reserved model calls to be preserved, got ${evidence.reservedCalls}`);
      if (!(reservedCalls > 0 && reservedCalls <= GRAPH_THREAD_BUDGET.maxCalls)) {
        throw new VerifyError(`reserved calls ${evidence.reservedCalls} outside the frozen thread budget ${GRAPH_THREAD_BUDGET.maxCalls}`);
      }
      if (!(reservedTokens > 0 && reservedTokens <= GRAPH_THREAD_BUDGET.maxTokens)) {
        throw new VerifyError(`reserved tokens ${evidence.reservedTokens} outside the frozen thread budget ${GRAPH_THREAD_BUDGET.maxTokens}`);
      }
    }
  };

  await runP406BrowserChecks({
    config: { ports },
    guard: () => {
      if (!existsSync(join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar'))) throw new VerifyError('core jar disappeared');
    },
    log: (message) => console.log(message),
    invoices: { graph: graphCase.invoiceNumber, off: offCase.invoiceNumber },
    onBeforeStep,
  });

  // Immutable DB evidence: the case's first frozen snapshot must carry the
  // exact completed graph proposal id, payload hash and context hash (parsed,
  // not a substring of the whole payload), and the graph review/business
  // effects must be the real committed rows.
  evidence.reviewSnapshotPayload = await sql(`select payload::text from review_snapshot where invoice_case_id='${graphCase.id}' order by snapshot_number asc limit 1`);
  const frozenSnapshot = JSON.parse(evidence.reviewSnapshotPayload);
  const frozenProposal = frozenSnapshot.proposal ?? {};
  evidence.snapshotProposalIdMatches = frozenProposal.id === evidence.graphRunId;
  evidence.snapshotProposalPayloadHashMatches = frozenProposal.payloadHash === evidence.graphPayloadHash;
  evidence.snapshotProposalContextHashMatches = frozenProposal.contextHash === evidence.graphContextHash;
  if (!evidence.snapshotProposalIdMatches || !evidence.snapshotProposalPayloadHashMatches || !evidence.snapshotProposalContextHashMatches) {
    throw new VerifyError('frozen snapshot does not carry the exact graph proposal id/payloadHash/contextHash');
  }
  evidence.snapshotProposal = { id: frozenProposal.id, payloadHash: frozenProposal.payloadHash, contextHash: frozenProposal.contextHash };
  evidence.graphReviewId = await sql(`select id from graph_review where run_id='${evidence.graphRunId}' order by created_at asc limit 1`);
  evidence.graphReviewConfirmation = await sql(`select confirmation::text from graph_review where run_id='${evidence.graphRunId}' order by created_at asc limit 1`);
  const confirmation = JSON.parse(evidence.graphReviewConfirmation);
  const itemDecisions = Array.isArray(confirmation.itemDecisions) ? confirmation.itemDecisions : [];
  const candidateDecision = itemDecisions.find((decision) => decision.lineNumber === 1);
  const unresolvedDecision = itemDecisions.find((decision) => decision.lineNumber === 2);
  evidence.graphReviewItemDecisions = itemDecisions.map((decision) => ({
    lineNumber: decision.lineNumber, itemId: decision.itemId, purchaseOrderLineId: decision.purchaseOrderLineId,
  }));
  evidence.graphReviewHasCandidateChoice = candidateDecision?.itemId === 'ITEM-A4-80' && candidateDecision?.purchaseOrderLineId === 'POL-1001-1';
  evidence.graphReviewHasUnresolved = unresolvedDecision?.itemId === null && unresolvedDecision?.purchaseOrderLineId === null;
  if (itemDecisions.length !== 2 || !evidence.graphReviewHasCandidateChoice || !evidence.graphReviewHasUnresolved) {
    throw new VerifyError(`stored confirmation does not record the exact ITEM-A4-80/POL-1001-1 candidate pair and the exact null unresolved pair: ${evidence.graphReviewConfirmation}`);
  }
  const graphStatus = await sql(`select status from graph_run where invoice_case_id='${graphCase.id}' order by created_at desc limit 1`);
  const successorCount = await sql(`select count(*) from graph_successor where invoice_case_id='${graphCase.id}'`);
  evidence.latestGraphStatus = graphStatus;
  evidence.successorCount = successorCount;
  if (graphStatus !== 'QUEUED') throw new VerifyError(`expected the successor run to be QUEUED, got ${graphStatus}`);
  if (successorCount !== '1') throw new VerifyError(`expected exactly one successor row, got ${successorCount}`);
  // AI-off approval must be a real committed payment request and one receipt
  // allocation: a missing allocation is a failure, never an 'n/a' pass.
  evidence.offPaymentRequests = await sql(`select count(*) from payment_request where invoice_case_id='${offCase.id}'`);
  evidence.offAllocations = await sql(`select count(*) from receipt_allocation where invoice_case_id='${offCase.id}'`);
  if (evidence.offPaymentRequests !== '1') throw new VerifyError(`AI-off approval did not create exactly one payment request, got ${evidence.offPaymentRequests}`);
  if (evidence.offAllocations !== '1') throw new VerifyError(`AI-off approval did not create exactly one receipt allocation, got ${evidence.offAllocations}`);
  record(`graph run latest status=${graphStatus} successorRows=${successorCount} resumeConsumption=${evidence.resumeConsumption}`);
  record(`frozen graph proof id/payloadHash/contextHash exact match; confirmation exact candidate+null pairs; AI-off paymentRequests=${evidence.offPaymentRequests} allocations=${evidence.offAllocations}`);
  writeFileSync(join(output, 'summary.json'), JSON.stringify({
    ok: true, steps, graphCase, offCase,
    evidence: {
      ...evidence,
      graphCaseId: graphCase.id, offCaseId: offCase.id,
      graphInvoice: graphCase.invoiceNumber, offInvoice: offCase.invoiceNumber,
      killExitCode: evidence.killExitCode, resumeConsumption: evidence.resumeConsumption,
      resumeAttempts: evidence.resumeAttempts, startAttempts: evidence.startAttempts,
      reservedCalls: evidence.reservedCalls, reservedTokens: evidence.reservedTokens,
      graphContextHash: evidence.graphContextHash, graphPayloadHash: evidence.graphPayloadHash,
      graphReviewId: evidence.graphReviewId,
    },
  }, null, 2));
} catch (error) {
  console.error(error.message);
  try {
    if (pg) {
      const runs = await sql(`select id,status,active_segment from graph_run order by created_at`);
      const dispatch = await sql(`select status,attempt_count,last_error_code from graph_dispatch order by created_at`).catch(() => 'n/a');
      console.error('DIAG graph_run=' + runs);
      console.error('DIAG graph_dispatch=' + dispatch);
    }
  } catch { /* diagnostics only */ }
  writeFileSync(join(output, 'failure.json'), JSON.stringify({ message: error.message, steps }, null, 2));
  for (const id of owned) {
    const result = await docker.run(['logs', id]);
    const safe = (result.stdout + result.stderr).replaceAll(workerToken, '[redacted]').replaceAll(rabbitPassword, '[redacted]')
      .replaceAll(pgPassword, '[redacted]').replaceAll(storagePassword, '[redacted]').replaceAll(storageUser, '[redacted]');
    writeFileSync(join(output, id.slice(0, 12) + '.log'), safe);
  }
  process.exitCode = 1;
} finally {
  for (const id of owned.reverse()) {
    if ((await docker.run(['rm', '-f', '-v', id])).code !== 0) { console.error('Owned container cleanup failed: ' + id.slice(0, 12)); process.exitCode = 1; }
  }
  if (networkCreated && (await docker.run(['network', 'rm', network])).code !== 0) { console.error('Owned network cleanup failed'); process.exitCode = 1; }
  console.log('INFO own resources reclaimed; evidence ' + output);
}
