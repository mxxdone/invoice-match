const crypto = require("node:crypto");
const http = require("node:http");

// P1-09 Mock ERP.
//
// The service is the external system of record for payment exports. It receives
// a payment export keyed by the P1-08 idempotency key (paymentRequestId:exportVersion),
// stores exactly one logical record per key, answers a status inquiry with the
// same key, and emits a signed follow-up webhook with the result.
//
// It is deliberately deterministic and dependency-free. The signing contract is
// shared with core-api:
//   X-Mock-Erp-Timestamp: <epoch seconds>
//   X-Mock-Erp-Signature: sha256=<HMAC-SHA256(secret, "<timestamp>.<raw body>")>

const PROVIDER = "mock-erp";
const EXPORT_PATH = "/api/payment-exports";
const INQUIRY_PATH = /^\/api\/payment-exports\/(.+)$/;
const MAX_BODY_BYTES = 64 * 1024;

function send(response, status, payload) {
  response.writeHead(status);
  response.end(JSON.stringify(payload));
}

function sha256Hex(text) {
  return crypto.createHash("sha256").update(text, "utf8").digest("hex");
}

function signWebhook(secret, timestamp, rawBody) {
  return (
    "sha256=" +
    crypto.createHmac("sha256", secret).update(`${timestamp}.${rawBody}`, "utf8").digest("hex")
  );
}

function buildWebhookBody(record) {
  return JSON.stringify({
    eventType: "PaymentExportResultReceived",
    externalEventId: record.externalEventId,
    externalPaymentKey: record.idempotencyKey,
    externalReference: record.externalReference,
    outcome: record.outcome,
    paymentRequestId: record.paymentRequestId,
    provider: record.provider,
  });
}

function readBody(request, onEnd, onTooLarge) {
  const chunks = [];
  let size = 0;
  request.on("data", (chunk) => {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      onTooLarge();
      request.destroy();
      return;
    }
    chunks.push(chunk);
  });
  request.on("end", () => onEnd(Buffer.concat(chunks).toString("utf8")));
  request.on("error", () => {});
}

function createMockErp(options = {}) {
  const webhookUrl = options.webhookUrl ?? process.env.WEBHOOK_URL ?? null;
  const webhookSecret = options.webhookSecret ?? process.env.WEBHOOK_SECRET ?? "";
  const autoWebhook = options.autoWebhook ?? true;
  const records = new Map();
  const state = { dropResponse: options.dropResponse ?? process.env.DROP_RESPONSE === "true" };

  function resultBody(record) {
    return {
      status: record.outcome === "ACKNOWLEDGED" ? "PROCESSED" : "REJECTED",
      outcome: record.outcome,
      externalPaymentKey: record.idempotencyKey,
      externalEventId: record.externalEventId,
      externalReference: record.externalReference,
    };
  }

  function deliverWebhook(record) {
    if (!webhookUrl) {
      return Promise.resolve(false);
    }
    const rawBody = buildWebhookBody(record);
    const timestamp = String(Math.floor(Date.now() / 1000));
    const headers = {
      "content-type": "application/json; charset=utf-8",
      "x-mock-erp-timestamp": timestamp,
    };
    if (webhookSecret) {
      headers["x-mock-erp-signature"] = signWebhook(webhookSecret, timestamp, rawBody);
    }
    return fetch(webhookUrl, { method: "POST", headers, body: rawBody })
      .then((response) => response.ok)
      .catch(() => false);
  }

  function handleExport(request, response, rawBody) {
    let parsed;
    try {
      parsed = JSON.parse(rawBody);
    } catch {
      return send(response, 400, { error: "INVALID_JSON" });
    }
    const idempotencyKey = request.headers["idempotency-key"];
    const paymentRequestId = request.headers["x-payment-request-id"];
    if (!idempotencyKey || !paymentRequestId) {
      return send(response, 400, { error: "MISSING_PAYMENT_IDENTITY" });
    }
    if (parsed.idempotencyKey != null && parsed.idempotencyKey !== idempotencyKey) {
      return send(response, 409, { error: "IDEMPOTENCY_KEY_MISMATCH" });
    }
    if (parsed.paymentRequestId != null && parsed.paymentRequestId !== paymentRequestId) {
      return send(response, 409, { error: "PAYMENT_REQUEST_ID_MISMATCH" });
    }

    const requestHash = sha256Hex(rawBody);
    const existing = records.get(idempotencyKey);
    if (existing) {
      if (existing.requestHash !== requestHash) {
        return send(response, 409, { error: "IDEMPOTENCY_CONFLICT" });
      }
      if (state.dropResponse) {
        response.destroy();
        return;
      }
      return send(response, 200, resultBody(existing));
    }

    const record = {
      provider: PROVIDER,
      idempotencyKey,
      paymentRequestId,
      requestHash,
      outcome: "ACKNOWLEDGED",
      externalEventId: "evt-" + crypto.randomUUID(),
      externalReference: "ERP-" + crypto.randomUUID(),
      receivedAt: new Date().toISOString(),
    };
    records.set(idempotencyKey, record);
    if (autoWebhook) {
      void deliverWebhook(record);
    }
    if (state.dropResponse) {
      // Processed and stored, but the HTTP response is lost. The same key
      // inquiry or the follow-up webhook converges core-api without a duplicate.
      response.destroy();
      return;
    }
    return send(response, 200, resultBody(record));
  }

  function handler(request, response) {
    response.setHeader("content-type", "application/json; charset=utf-8");
    const pathname = new URL(request.url, "http://localhost").pathname;

    if (request.method === "GET" && pathname === "/health") {
      return send(response, 200, { status: "UP" });
    }
    if (request.method === "POST" && pathname === EXPORT_PATH) {
      return readBody(
        request,
        (rawBody) => handleExport(request, response, rawBody),
        () => send(response, 413, { error: "PAYLOAD_TOO_LARGE" }),
      );
    }
    const inquiry = INQUIRY_PATH.exec(pathname);
    if (request.method === "GET" && inquiry) {
      const key = decodeURIComponent(inquiry[1]);
      const record = records.get(key);
      if (!record) {
        return send(response, 404, { error: "PAYMENT_EXPORT_NOT_FOUND", externalPaymentKey: key });
      }
      return send(response, 200, resultBody(record));
    }

    const knownPath = pathname === EXPORT_PATH || inquiry != null;
    return send(response, knownPath ? 405 : 404, knownPath ? { error: "METHOD_NOT_ALLOWED" } : { error: "Not found" });
  }

  return {
    handler,
    state,
    record: (key) => records.get(key),
    records,
    resultBody,
    deliverWebhook,
    setDropResponse: (value) => {
      state.dropResponse = Boolean(value);
    },
    reset: () => {
      records.clear();
      state.dropResponse = false;
    },
  };
}

const defaultInstance = createMockErp();

if (require.main === module) {
  const port = Number(process.env.PORT || 8081);
  http.createServer(defaultInstance.handler).listen(port, "0.0.0.0");
}

module.exports = {
  createMockErp,
  handler: defaultInstance.handler,
  buildWebhookBody,
  signWebhook,
  sha256Hex,
  PROVIDER,
};
