// Browser acceptance runner for P1-10.
//
// It is invoked from inside runVerification's verify callback, so it only ever
// talks to the throwaway stack that phase started; the existing cleanup stops
// that stack after this returns. It drives a real Chromium through the
// playwright-cli session it opens itself and closes in a finally, uses a finite
// timeout on every CLI call, streams the CLI log live, and writes screenshots to
// output/playwright/p1-10-complete-workflow.

import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { VerifyError } from '../lib/verify-core.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..', '..');

function runCli(args, { timeoutMs = 60000 } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const quote = (arg) => (/[\s"]/u.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg);
    const command = ['npx', '--yes', '--package', '@playwright/cli', 'playwright-cli', ...args]
      .map(quote)
      .join(' ');
    const child = spawn(command, { cwd: repo, shell: true, windowsHide: true });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
      rejectPromise(new VerifyError(`playwright-cli timed out after ${timeoutMs}ms: ${args.join(' ')}`));
    }, timeoutMs);
    child.stdout.on('data', (data) => { const text = data.toString(); stdout += text; process.stdout.write(text); });
    child.stderr.on('data', (data) => { const text = data.toString(); stderr += text; process.stderr.write(text); });
    child.on('error', (error) => { clearTimeout(timer); rejectPromise(error); });
    child.on('close', (code) => { clearTimeout(timer); resolvePromise({ code: code ?? 1, stdout, stderr }); });
  });
}

function parseRunCode(stdout) {
  const lines = stdout.split(/\r?\n/);
  const index = lines.findIndex((line) => line.trim() === '### Result');
  if (index === -1) return null;
  for (let i = index + 1; i < lines.length; i += 1) {
    const candidate = lines[i].trim();
    if (!candidate) continue;
    if (candidate.startsWith('{')) {
      try {
        return JSON.parse(candidate);
      } catch {
        return null;
      }
    }
    break;
  }
  return null;
}

export async function runBrowserChecks({ config, guard, log = () => {} }) {
  const webUrl = `http://127.0.0.1:${config.ports.web}`;
  const outDir = join(repo, 'output', 'playwright', 'p1-10-complete-workflow');
  mkdirSync(outDir, { recursive: true });

  const template = readFileSync(join(here, 'p1-10-flow.js'), 'utf8');
  const generated = template
    .replaceAll('__WEB__', webUrl)
    .replaceAll('__OUT__', outDir.replace(/\\/g, '/'));
  const generatedPath = join(outDir, 'flow.generated.js');
  writeFileSync(generatedPath, generated, 'utf8');

  const session = `p110-${process.pid}`;
  try {
    guard();
    const opened = await runCli(['-s', session, 'open', `${webUrl}/login`, '--idle-timeout', '180000'], { timeoutMs: 120000 });
    if (opened.code !== 0) throw new VerifyError('playwright-cli could not open the browser session');

    const run = await runCli(['-s', session, 'run-code', '--filename', generatedPath], { timeoutMs: 300000 });
    const summary = parseRunCode(run.stdout);
    if (run.code !== 0 || !summary) {
      const detail = run.stdout.split(/\r?\n/).filter((line) => /Error|ASSERT/.test(line)).join(' ').trim();
      throw new VerifyError(`browser flow failed${detail ? `: ${detail}` : ''}`);
    }
    if (summary.unexpectedHttpErrors && summary.unexpectedHttpErrors.length > 0) {
      throw new VerifyError(`browser unexpected HTTP errors: ${summary.unexpectedHttpErrors.join(' | ')}`);
    }
    if (summary.unexpectedConsoleErrors && summary.unexpectedConsoleErrors.length > 0) {
      throw new VerifyError(`browser console errors: ${summary.unexpectedConsoleErrors.join(' | ')}`);
    }
    for (const [name, value] of Object.entries(summary.results ?? {})) {
      if (value) log(`PASS browser ${name}`);
    }
    for (const mutation of summary.mutations ?? []) {
      log(`INFO browser write ${mutation}`);
    }
    log(`PASS browser flow (${(summary.steps ?? []).length} steps, ${outDir})`);
    return summary;
  } finally {
    try {
      await runCli(['-s', session, 'close'], { timeoutMs: 30000 });
    } catch (error) {
      log(`WARN browser session close: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
}
