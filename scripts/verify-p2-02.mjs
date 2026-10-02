// Build the fixed storage image and run the P2-02 document-evidence tests.
import { spawn, spawnSync } from 'node:child_process';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRunStreaming } from './lib/compose-core.mjs';
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const run = createRunStreaming({ spawnImpl: (command, args, options) => {
  const child = spawn(command, args, { ...options, detached: process.platform !== 'win32' });
  child.once('spawn', () => console.log(`INFO owned verification process ${command}, PID ${child.pid}`));
  child.kill = signal => {
    if (!child.pid) return false;
    if (process.platform === 'win32') {
      const result = spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'],
        { windowsHide: true, timeout: 10000, stdio: 'inherit' });
      if (result.error || result.status !== 0) throw new Error('Owned verification process tree cleanup failed');
    } else process.kill(-child.pid, signal);
    return true;
  };
  return child;
} });
const focused = process.argv.includes('--focused');
if (await run('docker', ['compose', '--env-file', '.env.example', '-f', 'compose.yaml',
  '-f', 'compose.storage.yaml', '-f', 'compose.documents.yaml', 'config', '--quiet'],
  { cwd: repo, timeoutMs: 30000, env: process.env }) !== 0) process.exit(1);
if (await run('docker', ['build', '--progress=plain', '-t', 'invoice-match-minio:p2-security-2025-10-15', 'infra/minio'],
  { cwd: repo, timeoutMs: 1200000, env: process.env }) !== 0) process.exit(1);
const args = ['-Dorg.gradle.appname=gradlew', '-classpath', 'gradle/wrapper/gradle-wrapper.jar',
  'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', 'test'];
if (focused) {
  for (const selector of [
    'com.invoicematch.core.document.*',
    'com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasherTest',
    'com.invoicematch.core.migration.V11UpgradeFromV10MigrationTest',
    'com.invoicematch.core.shared.ArchitectureLayeringTest',
    'com.invoicematch.core.approval.ApprovalForgeryIntegrationTest',
    'com.invoicematch.core.approval.ApprovalWorkflowIntegrationTest',
  ]) {
    args.push('--tests', selector);
  }
}
args.push('bootJar', '--console=plain');
process.exitCode = await run('java', args, { cwd: join(repo, 'core-api'), timeoutMs: 1200000, env: process.env });
