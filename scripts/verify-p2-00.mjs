// Isolated, bounded storage acceptance. Never touches the user's Compose project.
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runComposeSmoke, runStreaming, createRunCapture, sanitizeComposeEnv,
  validateDistinctPorts } from './lib/compose-core.mjs';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const project = `im-p200-${randomUUID().slice(0, 8)}`;
const output = join(repo, 'output', 'p2-00', project);
mkdirSync(output, { recursive: true });
const envPath = join(output, 'storage.env');
const overridePath = join(output, 'override.yaml');
const freePort = () => new Promise((accept, reject) => {
  const server = createServer();
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => {
    const port = server.address().port;
    server.close(error => error ? reject(error) : accept(port));
  });
});
const ports = { MINIO_API_PORT: await freePort(), MINIO_CONSOLE_PORT: await freePort() };
validateDistinctPorts(ports);
const generatedEnv = {
  MINIO_ROOT_USER: `test-${randomUUID()}`,
  MINIO_ROOT_PASSWORD: randomUUID(),
  ...ports,
};
const childEnv = sanitizeComposeEnv(process.env);
for (const key of Object.keys(generatedEnv)) delete childEnv[key];
const args = ['compose', '-p', project, '--env-file', envPath,
  '-f', 'compose.storage.yaml', '-f', overridePath];
const compose = (extra, timeoutMs = 60000) => runStreaming('docker', [...args, ...extra],
  { timeoutMs, cwd: repo, env: childEnv });
const capture = createRunCapture();
const steps = [];
const log = message => { console.log(message); steps.push(message); };
const checked = async (extra, timeoutMs) => {
  const code = await compose(extra, timeoutMs);
  if (code !== 0) throw new Error(`Compose ${extra[0]} exited ${code}`);
};
const mc = async body => checked(['exec', '-T', 'minio', '/bin/sh', '-ec', `
  export MC_CONFIG_DIR=/tmp/acceptance-mc
  trap 'rm -rf /tmp/acceptance-mc /tmp/acceptance-source /tmp/acceptance-result' EXIT
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  ${body}
`]);
const assertPrivate = async () => {
  for (const path of ['invoice-documents', 'invoice-documents/acceptance/fixture.txt']) {
    const response = await fetch(`http://127.0.0.1:${ports.MINIO_API_PORT}/${path}`,
      { signal: AbortSignal.timeout(10000) });
    await response.text();
    if (response.status !== 403) throw new Error(`Anonymous access returned ${response.status}`);
  }
};
const outcome = await runComposeSmoke({
  project, envPath, overridePath, generatedEnv,
  overrideYaml: 'services: {}\n', compose,
  writeExclusive: (path, text) => writeFileSync(path, text, { flag: 'wx', mode: 0o600 }),
  removeFile: path => rmSync(path), log, error: console.error,
  upTimeoutMs: 1200000, downTimeoutMs: 60000,
  configCheck: async () => {
    // Config contains credentials: capture privately and assert without printing it.
    const result = await capture('docker', [...args, 'config', '--format', 'json'],
      { timeoutMs: 30000, cwd: repo, env: childEnv });
    if (result.code !== 0) throw new Error('Storage Compose configuration failed');
    const config = JSON.parse(result.stdout);
    const published = config.services.minio.ports;
    if (published.length !== 2 || published.some(port => port.host_ip !== '127.0.0.1')) {
      throw new Error('Storage ports must bind only to loopback');
    }
    log('PASS Compose loopback bindings');
  },
  verify: async () => {
    log(`INFO owned project ${project}; API port ${ports.MINIO_API_PORT}; console port ${ports.MINIO_CONSOLE_PORT}`);
    await checked(['ps', '--all']);
    await checked(['run', '--rm', '--no-deps', 'minio-init']);
    await mc(`printf 'phase-two-storage-fixture' > /tmp/acceptance-source
      mc cp /tmp/acceptance-source local/invoice-documents/acceptance/fixture.txt
      mc cat local/invoice-documents/acceptance/fixture.txt > /tmp/acceptance-result
      cmp /tmp/acceptance-source /tmp/acceptance-result`);
    log('PASS authenticated object write/read byte equality');
    await assertPrivate();
    log('PASS anonymous bucket listing and object download denied');
    await checked(['run', '--rm', '--no-deps', 'minio-init']);
    await assertPrivate();
    log('PASS repeat bucket initialization preserves private access');
    await checked(['up', '-d', '--force-recreate', '--wait', '--wait-timeout', '60', 'minio'], 90000);
    await mc(`printf 'phase-two-storage-fixture' > /tmp/acceptance-source
      mc cat local/invoice-documents/acceptance/fixture.txt > /tmp/acceptance-result
      cmp /tmp/acceptance-source /tmp/acceptance-result`);
    await assertPrivate();
    log('PASS container recreation preserves object bytes and private access');
  },
});
writeFileSync(join(output, 'evidence.json'), JSON.stringify({
  generatedAt: new Date().toISOString(), project, ports, steps, ...outcome,
}, null, 2));
console.log(outcome.ok ? 'PASS P2-00 (owned containers/volume and secret files removed)' : 'FAIL P2-00');
for (const error of outcome.errors) console.error(error);
process.exitCode = outcome.exitCode;
