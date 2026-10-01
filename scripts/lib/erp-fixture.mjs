// P1-11 in-process Mock ERP fixture.
//
// The P1-11 acceptance runner must be able to prove the exact ordering of an
// ERP post-commit response loss (persisted RESULT_UNKNOWN before the signed
// result arrives) and to drive FAILED vs RESULT_UNKNOWN distinctly. The
// committed mock-erp module already exposes the needed seams
// (createMockErp({dropResponse, autoWebhook}), setDropResponse, record(s),
// deliverWebhook); this fixture hosts it inside the verification runner and
// exposes it through the same child-process lifecycle contract
// (name/alive/stop) that runVerification already owns and cleans up.
//
// It is test infrastructure only. It never changes the mock-erp HTTP contract
// or the core-api contract and it is not used by Compose.

import { createServer } from 'node:http';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { createMockErp, signWebhook } = require('../../mock-erp/server.js');

export const DEFAULT_ERP_HOST = '127.0.0.1';

export async function startErpFixture({
  name = 'mock-erp',
  port,
  host = DEFAULT_ERP_HOST,
  webhookUrl,
  webhookSecret,
  dropResponse = false,
  autoWebhook = false,
}) {
  const erp = createMockErp({ webhookUrl, webhookSecret, dropResponse, autoWebhook });
  const state = { delayMs: 0, exportRequests: 0, lastExportAt: null };
  // Test-only response delay: lets a scenario deterministically observe the
  // committed SENDING window (the relay flips to SENDING, then blocks on HTTP)
  // before a signed webhook resolves the same export. It is never used by
  // Compose or by the committed mock-erp HTTP contract.
  const handler = (request, response) => {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    if (request.method === 'POST' && pathname === '/api/payment-exports') {
      state.exportRequests += 1;
    }
    if (state.delayMs > 0 && request.method === 'POST' && pathname === '/api/payment-exports') {
      // Record completion time when the delayed response is actually produced,
      // so a scenario can prove a late HTTP success arrived after the webhook
      // had already terminalized the outbox.
      setTimeout(() => {
        state.lastExportAt = Date.now();
        erp.handler(request, response);
      }, state.delayMs);
      return;
    }
    erp.handler(request, response);
  };
  const server = createServer(handler);
  // Track this fixture's own sockets so a delayed/pending response cannot keep
  // the process alive during a bounded shutdown.
  const sockets = new Set();
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });
  let closed = false;

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolve());
  });

  return {
    name,
    erp,
    baseUrl: `http://${host}:${port}`,
    sign: (timestamp, rawBody) => signWebhook(webhookSecret, timestamp, rawBody),
    setDelayMs: (value) => { state.delayMs = Number(value) || 0; },
    setDropResponse: (value) => erp.setDropResponse(value),
    exportRequestCount: () => state.exportRequests,
    lastExportAt: () => state.lastExportAt,
    alive() {
      return !closed && server.listening;
    },
    async stop() {
      if (closed) {
        return { ok: true };
      }
      closed = true;
      const closedPromise = new Promise((resolve) => server.close(() => resolve()));
      // Release only the connections this fixture owns so shutdown cannot hang
      // on a delayed export response.
      for (const socket of sockets) {
        try { socket.destroy(); } catch { /* best effort */ }
      }
      const outcome = await Promise.race([
        closedPromise.then(() => 'closed'),
        new Promise((resolve) => setTimeout(() => resolve('timeout'), 3000)),
      ]);
      return outcome === 'closed'
        ? { ok: true }
        : { ok: false, error: 'mock-erp fixture server did not close within 3000ms' };
    },
  };
}
