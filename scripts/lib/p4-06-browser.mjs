// Browser acceptance runner for P4-06.
//
// Invoked from inside runVerification's verify callback, so it only talks to the
// throwaway stack that phase started; the existing cleanup stops that stack after
// this returns. It drives a real Chromium through one playwright-cli session it
// opens and closes in a finally, uses a finite timeout on every CLI call, streams
// the CLI log live, runs the graph flow in ordered steps (so the worker
// stop/restart between steps happens outside the browser), and writes
// screenshots plus a summary under output/playwright/p4-06.
//
// No @playwright/test is used; only the CLI-first `playwright-cli run-code`.

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
      try { return JSON.parse(candidate); } catch { return null; }
    }
    break;
  }
  return null;
}

export async function runP406BrowserChecks({ config, guard, log = () => {}, invoices, onBeforeStep = async () => {} }) {
  if (!invoices || !invoices.graph || !invoices.off) {
    throw new VerifyError('P4-06 browser checks require the prepared graph and AI-off fixtures');
  }
  const webUrl = `http://127.0.0.1:${config.ports.web}`;
  const offWebUrl = `http://127.0.0.1:${config.ports.webOff}`;
  const outDir = join(repo, 'output', 'playwright', 'p4-06');
  mkdirSync(outDir, { recursive: true });

  const template = readFileSync(join(here, '..', 'browser', 'p4-06-flow.js'), 'utf8');
  const render = (step) => {
    const generated = template
      .replaceAll('__WEB__', webUrl)
      .replaceAll('__OFFWEB__', offWebUrl)
      .replaceAll('__OUT__', outDir.replace(/\\/g, '/'))
      .replaceAll('__INVOICE__', invoices.graph)
      .replaceAll('__OFFINVOICE__', invoices.off)
      .replaceAll('__STEP__', step);
    const generatedPath = join(outDir, `flow-${step}.generated.js`);
    writeFileSync(generatedPath, generated, 'utf8');
    return generatedPath;
  };

  const session = `p406-${process.pid}`;
  const stepsOrder = ['reserve-confirm', 'verify-completed', 'mapping', 'successor', 'submitter-read', 'ai-off'];
  const summaries = [];
  let flowError = null;
  try {
    guard();
    const opened = await runCli(['-s', session, 'open', `${webUrl}/login`, '--idle-timeout', '240000'], { timeoutMs: 120000 });
    if (opened.code !== 0) throw new VerifyError('playwright-cli could not open the browser session');
    for (const step of stepsOrder) {
      await onBeforeStep(step);
      const run = await runCli(['-s', session, 'run-code', '--filename', render(step)], { timeoutMs: 300000 });
      const summary = parseRunCode(run.stdout);
      if (run.code !== 0 || !summary) {
        const detail = run.stdout.split(/\r?\n/).filter((line) => /Error|ASSERT|Timed out/.test(line)).join(' ').trim();
        throw new VerifyError(`browser step ${step} failed${detail ? `: ${detail}` : ''}`);
      }
      if (summary.unexpectedHttpErrors && summary.unexpectedHttpErrors.length > 0) {
        throw new VerifyError(`browser ${step} unexpected HTTP errors: ${summary.unexpectedHttpErrors.join(' | ')}`);
      }
      if (summary.unexpectedConsoleErrors && summary.unexpectedConsoleErrors.length > 0) {
        throw new VerifyError(`browser ${step} console errors: ${summary.unexpectedConsoleErrors.join(' | ')}`);
      }
      summaries.push(summary);
      log(`PASS browser ${step}`);
    }
    const summaryPath = join(outDir, 'browser-summary.json');
    writeFileSync(summaryPath, JSON.stringify({
      webOrigin: webUrl,
      offWebOrigin: offWebUrl,
      assertedSource: 'real Chromium against the isolated Core/PostgreSQL/RabbitMQ/Linux worker stack',
      capturedAt: new Date().toISOString(),
      results: Object.assign({}, ...summaries.map((s) => s.results)),
      steps: summaries.flatMap((s) => s.steps),
      httpErrors: summaries.flatMap((s) => s.httpErrors ?? []),
      consoleErrors: summaries.flatMap((s) => s.consoleErrors ?? []),
    }, null, 2), 'utf8');
    log(`INFO browser summary ${summaryPath}`);
    for (const [name, value] of Object.entries(Object.assign({}, ...summaries.map((s) => s.results)))) {
      if (value) log(`PASS browser ${name}`);
    }
    log(`PASS browser flow (${stepsOrder.length} steps, ${outDir})`);
  } catch (error) {
    flowError = error instanceof Error ? error : new VerifyError(String(error));
  }

  let closeError = null;
  try {
    const closed = await runCli(['-s', session, 'close'], { timeoutMs: 30000 });
    if (closed.code !== 0) closeError = new VerifyError(`playwright-cli session close exited ${closed.code}`);
  } catch (error) {
    closeError = error instanceof Error ? error : new VerifyError(String(error));
  }

  if (flowError && closeError) throw new VerifyError(`${flowError.message}; browser session close also failed: ${closeError.message}`);
  if (flowError) throw flowError;
  if (closeError) throw closeError;
  return summaries;
}
