// Real services, latest jar + installed Linux wheel, isolated throwaway resources.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { createServer } from 'node:net';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createDocker } from './lib/adapters.mjs';
import { createRunStreaming } from './lib/compose-core.mjs';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const prefix = 'im-p208-' + randomUUID().slice(0, 8);
const output = join(repo, 'output', 'p2-08', prefix);
mkdirSync(output, { recursive: true });
const docker = createDocker({ timeoutMs: 30000 });
const run = createRunStreaming();
const network = prefix + '-network';
const owned = [];
const logReaders = [];
let networkCreated = false;
const resources = () => writeFileSync(join(output, 'resources.json'), JSON.stringify({ network, owned }, null, 2));
const pgPassword = randomUUID(), storageUser = 'test-' + randomUUID(), storagePassword = randomUUID();
const rabbitUser = 'test-' + randomUUID(), rabbitPassword = randomUUID(), workerToken = randomUUID() + randomUUID();
const controlToken = randomUUID();
let pg, rabbit, worker;
const steps = [];
const record = label => { steps.push(label); console.log('PASS ' + label); };
const sleep = ms => new Promise(done => setTimeout(done, ms));
async function command(args) {
  const result = await docker.run(args);
  if (result.code !== 0) throw Error(`Docker operation failed: ${args[0]}`);
  return result.stdout.trim();
}
async function port() {
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
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await sleep(400);
  }
  throw Error('Timed out: ' + label);
}
async function ready(url, id) {
  await wait(url, async () => {
    assert.equal(await command(['inspect', '--format', '{{.State.Running}}', id]), 'true', 'owned service exited');
    try { return (await fetch(url, { signal: AbortSignal.timeout(2000) })).ok; } catch { return false; }
  });
}
async function sql(statement) {
  const result = await docker.exec(pg, ['psql', '-U', 'invoice_match', '-d', 'invoice_match', '-v', 'ON_ERROR_STOP=1', '-tAc', statement]);
  if (result.code !== 0) throw Error('Database observation failed');
  return result.stdout.trim();
}
const ports = { core: await port(), minio: await port(), proxy: await port() };
const coreUrl = `http://127.0.0.1:${ports.core}`;
async function api(method, path, body, actor = 'submitter') {
  const response = await fetch(coreUrl + path, { method, signal: AbortSignal.timeout(15000),
    headers: { 'content-type': 'application/json', authorization: 'Basic ' + Buffer.from(actor + ':' + actor + '-pass').toString('base64') },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const value = await response.json();
  assert(response.ok, `API ${method} ${path}: ${response.status} ${value.code ?? ''}`);
  return value;
}
async function makeCase(kinds = ['pdf', 'xlsx']) {
  console.log('INFO creating submitted fixture: ' + kinds.join(','));
  const created = await api('POST', '/api/invoice-cases', { requestId: randomUUID(), supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber: prefix + '-' + randomUUID() });
  const id = created.id;
  const draft = await api('PUT', `/api/invoice-cases/${id}/draft`, { requestId: randomUUID(), expectedCaseVersion: created.version,
    lines: [{ lineNumber: 1, rawItemName: 'Copy Paper', quantity: 5, unitPrice: 2500, confirmedItemId: 'ITEM-A4-80' }] });
  let version = draft.version;
  const revision = await sql(`select current_draft_revision_id from invoice_case where id='${id}'`);
  for (const kind of kinds) {
    const bytes = kind === 'broken' ? Buffer.from('%PDF-invalid') : readFileSync(join(output, 'sample.' + kind));
    const mediaType = kind === 'xlsx' ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' : 'application/pdf';
    const checksum = createHash('sha256').update(bytes).digest('hex');
    const reserved = await api('POST', `/api/invoice-cases/${id}/documents/presign`, { requestId: randomUUID(), expectedCaseVersion: version,
      draftRevisionId: revision, fileName: 'sample.' + (kind === 'broken' ? 'pdf' : kind), mediaType, sizeBytes: bytes.length, checksum });
    const uploaded = await fetch(reserved.uploadUrl, { method: 'PUT', headers: reserved.requiredHeaders, body: bytes, signal: AbortSignal.timeout(10000) });
    assert(uploaded.ok, 'presigned upload failed');
    const completed = await api('POST', `/api/invoice-cases/${id}/documents/complete`, { requestId: randomUUID(), expectedCaseVersion: version,
      draftRevisionId: revision, documentId: reserved.documentId, checksum });
    version = completed.caseVersion;
  }
  const submitted = await api('POST', `/api/invoice-cases/${id}/submit`, { requestId: randomUUID(), expectedCaseVersion: version });
  const runId = await sql(`select id from analysis_run where invoice_case_id='${id}' and input_version=1`);
  return { id, runId, caseStatus: submitted.status, caseVersion: submitted.version };
}
async function finished(runId, status = 'COMPLETED') {
  await wait(status, async () => (await sql(`select status from analysis_run where id='${runId}'`)) === status);
}
async function rabbitCommand(args) {
  // A root CLI during first boot can create a root-owned .erlang.cookie before
  // the broker user does. Readiness must run under the broker's own identity.
  return docker.run(['exec', '--user', 'rabbitmq', rabbit, ...args]);
}
async function queueEmpty() {
  await wait('ACK', async () => {
    const result = await rabbitCommand(['rabbitmqctl', '-q', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged']);
    return result.code === 0 && /invoice\.analysis\.requests\s+0\s+0/.test(result.stdout);
  });
}
async function publish(runId) {
  const payload = await sql(`select payload::text from analysis_request_outbox where analysis_run_id='${runId}'`);
  const code = 'import os,sys,json,pika; p=json.loads(sys.argv[1]); c=pika.BlockingConnection(pika.ConnectionParameters(host="rabbit",credentials=pika.PlainCredentials(os.environ["ANALYSIS_RABBIT_USERNAME"],os.environ["ANALYSIS_RABBIT_PASSWORD"]),socket_timeout=2,stack_timeout=5,heartbeat=10)); ch=c.channel(); ch.basic_publish(exchange="invoice.analysis",routing_key="document-parser-v1",body=sys.argv[1].encode(),properties=pika.BasicProperties(content_type="application/json",content_encoding="UTF-8",type="InvoiceAnalysisRequested",message_id=p["eventId"],delivery_mode=2)); c.close()';
  const result = await docker.exec(worker, ['/runtime/venv/bin/python', '-c', code, payload]);
  assert.equal(result.code, 0, 'duplicate publisher failed');
}
async function startWorker() {
  if (worker) {
    await command(['start', worker]);
  } else {
    const shell = 'set -e\npython -m venv /runtime/venv\nV=/runtime/venv/bin\n$V/python -m pip install --disable-pip-version-check --cache-dir /cache -r /w/requirements-dev.txt\nmkdir -p /runtime/pkg\ntar --exclude="__pycache__" --exclude="*.pyc" -cf - -C /w src pyproject.toml README.md | tar -xf - -C /runtime/pkg\n$V/python -m pip install --disable-pip-version-check --no-build-isolation --no-deps /runtime/pkg\nexec $V/ai-worker consume';
    worker = await container('worker', 'python:3.12-slim', ['--memory', '1g', '--pids-limit', '256',
      '--tmpfs', '/runtime:rw,exec,size=256m',
      '-v', join(repo, 'ai-worker') + ':/w:ro', '-v', output + ':/out', '-v', join(repo, 'output', 'p2-04', 'cache', 'pip') + ':/cache'],
      { CORE_API_URL: 'http://proxy:8080', ANALYSIS_WORKER_TOKEN: workerToken, ANALYSIS_RABBIT_HOST: 'rabbit',
        ANALYSIS_RABBIT_USERNAME: rabbitUser, ANALYSIS_RABBIT_PASSWORD: rabbitPassword }, ['sh', '-lc', shell]);
  }
  const logs = spawn('docker', ['logs', '--follow', '--since', '1s', worker], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  logReaders.push(logs);
  const safe = data => process.stdout.write(data.toString().replaceAll(workerToken, '[redacted]').replaceAll(storagePassword, '[redacted]').replaceAll(rabbitPassword, '[redacted]').replaceAll(pgPassword, '[redacted]'));
  logs.stdout.on('data', safe); logs.stderr.on('data', safe);
  logs.on('error', () => { process.exitCode = 1; });
  await wait('worker consumer', async () => {
    assert.equal(await command(['inspect', '--format', '{{.State.Running}}', worker]), 'true', 'worker exited before ready');
    const result = await rabbitCommand(['rabbitmqctl', '-q', 'list_queues', 'name', 'consumers']);
    return result.code === 0 && /invoice\.analysis\.requests\s+1/.test(result.stdout);
  });
  console.log('INFO consumer ready');
}

try {
  assert(existsSync(join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar')), 'Build the latest bootJar first');
  const fixture = 'import sys,pathlib;sys.path.insert(0,"ai-worker/tests");from fixtures import build_pdf,build_xlsx,SheetSpec,CellSpec;p=pathlib.Path(sys.argv[1]);(p/"sample.pdf").write_bytes(build_pdf(["Invoice 5 x 2500",None]));(p/"sample.xlsx").write_bytes(build_xlsx([SheetSpec("Invoice",[CellSpec("A1",kind="inlineStr",inline="Copy Paper"),CellSpec("B1",value="5"),CellSpec("C1",value="2500")])]))';
  assert.equal(await run(process.env.PYTHON_BIN ?? 'python', ['-c', fixture, output], { cwd: repo, env: process.env, timeoutMs: 30000 }), 0);
  await command(['network', 'create', network]); networkCreated = true; resources();
  pg = await container('postgres', 'postgres:18-alpine', ['--tmpfs', '/var/lib/postgresql:rw,size=256m'],
      { POSTGRES_DB: 'invoice_match', POSTGRES_USER: 'invoice_match', POSTGRES_PASSWORD: pgPassword });
  await wait('postgres', async () => (await docker.exec(pg, ['pg_isready', '-h', '127.0.0.1', '-U', 'invoice_match'])).code === 0);
  const minio = await container('minio', 'invoice-match-minio:p2-security-2025-10-15', ['-p', `127.0.0.1:${ports.minio}:9000`,
      '--tmpfs', '/data:rw,size=128m,uid=10001,gid=10001'], { MINIO_ROOT_USER: storageUser, MINIO_ROOT_PASSWORD: storagePassword });
  await ready(`http://127.0.0.1:${ports.minio}/minio/health/ready`, minio);
  await wait('bucket initialization', async () => (await docker.exec(minio, ['sh', '-ec',
      'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc mb --ignore-existing local/invoice-documents >/dev/null; mc anonymous set none local/invoice-documents >/dev/null'])).code === 0);
  rabbit = await container('rabbit', 'rabbitmq:4.2-alpine', ['--memory', '512m'],
      { RABBITMQ_DEFAULT_USER: rabbitUser, RABBITMQ_DEFAULT_PASS: rabbitPassword });
  await wait('rabbit', async () => {
    assert.equal(await command(['inspect', '--format', '{{.State.Running}}', rabbit]), 'true', 'RabbitMQ exited');
    return (await rabbitCommand(['rabbitmq-diagnostics', '-q', 'ping'])).code === 0;
  });
  const nodeImage = process.env.NODE_RUNTIME_IMAGE ?? 'invoice-match-mock-erp:latest';
  await container('purchasing', nodeImage, ['--entrypoint', 'node', '-v', join(repo, 'mock-purchasing', 'server.js') + ':/verify/server.cjs:ro'],
      { PORT: '8082' }, ['/verify/server.cjs']);
  const core = await container('core', process.env.CORE_RUNTIME_IMAGE ?? 'invoice-match-core-api:latest',
      ['--entrypoint', 'java', '--memory', '768m', '-p', `127.0.0.1:${ports.core}:8080`,
       '-v', join(repo, 'core-api', 'build', 'libs', 'core-api-0.1.0-SNAPSHOT.jar') + ':/verify/current.jar:ro'],
      { DB_URL: 'jdbc:postgresql://postgres:5432/invoice_match', DB_USER: 'invoice_match', DB_PASSWORD: pgPassword,
        PURCHASING_BASE_URL: 'http://purchasing:8082', SPRING_PROFILES_ACTIVE: 'local',
        DOCUMENT_STORAGE_ENABLED: 'true', DOCUMENT_STORAGE_ENDPOINT: 'http://minio:9000',
        DOCUMENT_STORAGE_PUBLIC_ENDPOINT: `http://127.0.0.1:${ports.minio}`, DOCUMENT_STORAGE_ACCESS_KEY: storageUser,
        DOCUMENT_STORAGE_SECRET_KEY: storagePassword, ANALYSIS_REQUEST_ENABLED: 'true', ANALYSIS_RELAY_ENABLED: 'true',
        ANALYSIS_RELAY_INTERVAL: '1s', ANALYSIS_RABBIT_HOST: 'rabbit', ANALYSIS_RABBIT_USERNAME: rabbitUser,
        ANALYSIS_RABBIT_PASSWORD: rabbitPassword, ANALYSIS_WORKER_ENABLED: 'true', ANALYSIS_WORKER_TOKEN: workerToken },
      ['-Xmx384m', '-jar', '/verify/current.jar']);
  await ready(coreUrl + '/actuator/health', core);
  const proxy = await container('proxy', nodeImage, ['--entrypoint', 'node', '-p', `127.0.0.1:${ports.proxy}:8080`,
       '-v', join(repo, 'scripts', 'lib', 'analysis-verification-proxy.mjs') + ':/verify/proxy.mjs:ro'],
      { CORE_API_URL: 'http://core:8080', CONTROL_TOKEN: controlToken }, ['/verify/proxy.mjs']);
  await ready(`http://127.0.0.1:${ports.proxy}/health`, proxy);
  await startWorker();
  const normal = await makeCase();
  await finished(normal.runId); await queueEmpty();
  assert.equal(await sql(`select count(*) from analysis_document_result where run_id='${normal.runId}'`), '2');
  assert.equal(await sql(`select status from invoice_case where id='${normal.id}'`), normal.caseStatus);
  assert.equal(await sql(`select version from invoice_case where id='${normal.id}'`), String(normal.caseVersion));
  record('PDF + XLSX: real submit/relay/source/isolated parser/results/ACK');
  await publish(normal.runId); await queueEmpty();
  assert.equal(await sql(`select count(*) from analysis_document_result where run_id='${normal.runId}'`), '2');
  record('same event redelivery preserves immutable result count');
  const broken = await makeCase(['broken']);
  await finished(broken.runId, 'FAILED'); await queueEmpty();
  assert.equal(await sql(`select error_code from analysis_document_result where run_id='${broken.runId}'`), 'PDF_CORRUPT');
  record('typed parser failure is durable and acknowledged');
  await command(['stop', '-t', '60', worker]);
  const partial = await makeCase();
  const configured = await fetch(`http://127.0.0.1:${ports.proxy}/control/drop-result`, { method: 'POST',
    headers: { 'x-verification-control': controlToken }, body: JSON.stringify({ runId: partial.runId }), signal: AbortSignal.timeout(5000) });
  assert(configured.ok);
  await startWorker();
  await wait('fail-stop after response loss', async () => (await command(['inspect', '--format', '{{.State.Running}}', worker])) === 'false');
  assert.equal(await sql(`select status from analysis_run where id='${partial.runId}'`), 'RUNNING');
  assert.equal(await sql(`select count(*) from analysis_document_result where run_id='${partial.runId}'`), '1');
  await wait('real execution lease expiry', async () => (await sql(`select lease_until < clock_timestamp() from analysis_run where id='${partial.runId}'`)) === 't', 130000);
  await startWorker(); await finished(partial.runId); await queueEmpty();
  assert.equal(await sql(`select count(*) from analysis_document_result where run_id='${partial.runId}'`), '2');
  assert.equal(await sql(`select execution_attempt from analysis_run where id='${partial.runId}'`), '2');
  record('committed partial result + lost response: no ACK, lease reclaim and immutable replay');
  await command(['stop', '-t', '60', worker]);
  const stale = await makeCase(['pdf']);
  await wait('old request published', async () => (await sql(`select status from analysis_request_outbox where analysis_run_id='${stale.runId}'`)) === 'PUBLISHED');
  await api('POST', `/api/invoice-cases/${stale.id}/match`, { requestId: randomUUID() }, 'operator');
  let detail = await api('GET', `/api/invoice-cases/${stale.id}`, undefined, 'approver');
  const snapshot = await api('POST', `/api/invoice-cases/${stale.id}/review-snapshots`, { requestId: randomUUID(), expectedCaseVersion: detail.version }, 'approver');
  detail = await api('GET', `/api/invoice-cases/${stale.id}`, undefined, 'approver');
  await api('POST', `/api/invoice-cases/${stale.id}/supplement-requests`, { requestId: randomUUID(), expectedCaseVersion: detail.version,
    reviewSnapshotId: snapshot.id, reviewPayloadHash: snapshot.payloadHash, reason: 'verification supplement' }, 'approver');
  detail = await api('GET', `/api/invoice-cases/${stale.id}`, undefined, 'approver');
  const opened = await api('POST', `/api/invoice-cases/${stale.id}/revisions`, { requestId: randomUUID(), expectedCaseVersion: detail.version });
  await api('POST', `/api/invoice-cases/${stale.id}/submit`, { requestId: randomUUID(), expectedCaseVersion: opened.version });
  await startWorker();
  const latest = await sql(`select id from analysis_run where invoice_case_id='${stale.id}' and input_version=2`);
  await finished(latest); await queueEmpty();
  assert.equal(await sql(`select status from analysis_run where id='${stale.runId}'`), 'STALE');
  assert.equal(await sql(`select count(*) from analysis_document_result where run_id='${stale.runId}'`), '0');
  record('real supplement makes old input STALE before consumer reads source');
  writeFileSync(join(output, 'summary.json'), JSON.stringify({ ok: true, steps }, null, 2));
} catch (error) {
  console.error(error.message);
  writeFileSync(join(output, 'failure.json'), JSON.stringify({ message: error.message, steps }, null, 2));
  for (const id of owned) {
    const result = await docker.run(['logs', id]);
    const safe = (result.stdout + result.stderr).replaceAll(workerToken, '[redacted]').replaceAll(storagePassword, '[redacted]').replaceAll(rabbitPassword, '[redacted]').replaceAll(pgPassword, '[redacted]');
    writeFileSync(join(output, id.slice(0, 12) + '.log'), safe);
  }
  process.exitCode = 1;
} finally {
  for (const id of owned.reverse()) {
    if ((await docker.run(['rm', '-f', '-v', id])).code !== 0) { console.error('Owned container cleanup failed'); process.exitCode = 1; }
  }
  if (networkCreated && (await docker.run(['network', 'rm', network])).code !== 0) { console.error('Owned network cleanup failed'); process.exitCode = 1; }
  for (const logs of logReaders) if (logs.exitCode === null) logs.kill();
  console.log('INFO own resources reclaimed; evidence ' + output);
}
