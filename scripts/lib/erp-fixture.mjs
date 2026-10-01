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
// It also records, per export request, whether the HTTP response actually
// FINISHED (a real status was written) versus closed without a response
// (dropResponse) versus is still open. That distinction is required so a late
// real 2xx is never inferred from request arrival or a fixed sleep.
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
  const state = { delayMs: 0, exportRequests: 0, exportCompletions: [] };

  const handler = (request, response) => {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    if (request.method === 'POST' && pathname === '/api/payment-exports') {
      const record = {
        requestedAt: Date.now(),
        status: null,
        completedAt: null,
        finished: false,
        closedWithoutFinish: false,
      };
      state.exportRequests += 1;
      state.exportCompletions.push(record);
      response.on('finish', () => {
        record.finished = true;
        record.status = response.statusCode;
        record.completedAt = Date.now();
      });
      response.on('close', () => {
        if (!record.finished) {
          record.closedWithoutFinish = true;
          record.completedAt = Date.now();
        }
      });
      if (state.delayMs > 0) {
        setTimeout(() => erp.handler(request, response), state.delayMs);
        return;
      }
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
  const bound = server.address();
  const boundPort = bound && typeof bound === 'object' ? bound.port : port;

  return {
    name,
    erp,
    baseUrl: `http://${host}:${boundPort}`,
    sign: (timestamp, rawBody) => signWebhook(webhookSecret, timestamp, rawBody),
    setDelayMs: (value) => { state.delayMs = Number(value) || 0; },
    setDropResponse: (value) => erp.setDropResponse(value),
    exportRequestCount: () => state.exportRequests,
    exportCompletions: () => state.exportCompletions.map((record) => ({ ...record })),
    // The last request whose HTTP response actually FINISHED (a real status was
    // written). A dropped or still-open request is never returned here.
    lastCompletedExport: () => {
      for (let i = state.exportCompletions.length - 1; i >= 0; i -= 1) {
        if (state.exportCompletions[i].finished) {
          return { ...state.exportCompletions[i] };
        }
      }
      return null;
    },
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
