const assert = require("node:assert/strict");
const http = require("node:http");
const test = require("node:test");
const { createMockErp, PROVIDER, signWebhook } = require("./server");

async function withServer(instance, run) {
  const server = http.createServer(instance.handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await run(base);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

function exportPayload(key, paymentId, extra = {}) {
  return JSON.stringify({
    eventType: "PaymentRequestExportRequested",
    idempotencyKey: key,
    paymentRequestId: paymentId,
    amount: 100000,
    ...extra,
  });
}

function postExport(base, key, paymentId, body) {
  return fetch(`${base}/api/payment-exports`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": key,
      "x-payment-request-id": paymentId,
    },
    body: body ?? exportPayload(key, paymentId),
  });
}

test("health is deterministic and other routes are absent", async () => {
  const instance = createMockErp();
  await withServer(instance, async (base) => {
    const health = await fetch(`${base}/health`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: "UP" });
    const missing = await fetch(`${base}/other`);
    assert.equal(missing.status, 404);
  });
});

test("same key and same payload replay the stored result with one record", async () => {
  const instance = createMockErp();
  await withServer(instance, async (base) => {
    const key = "11111111-1111-1111-1111-111111111111:1";
    const id = "11111111-1111-1111-1111-111111111111";

    const first = await postExport(base, key, id);
    assert.equal(first.status, 200);
    const firstBody = await first.json();
    assert.equal(firstBody.outcome, "ACKNOWLEDGED");
    assert.equal(firstBody.externalPaymentKey, key);

    const replay = await postExport(base, key, id);
    assert.equal(replay.status, 200);
    assert.deepEqual(await replay.json(), firstBody);
    assert.equal(instance.records.size, 1);
  });
});

test("same key with a different payload is a conflict and does not change the record", async () => {
  const instance = createMockErp();
  await withServer(instance, async (base) => {
    const key = "22222222-2222-2222-2222-222222222222:1";
    const id = "22222222-2222-2222-2222-222222222222";
    const first = await postExport(base, key, id);
    const stored = await first.json();

    const conflict = await postExport(base, key, id, exportPayload(key, id, { amount: 999 }));
    assert.equal(conflict.status, 409);
    assert.equal((await conflict.json()).error, "IDEMPOTENCY_CONFLICT");
    assert.deepEqual(instance.resultBody(instance.record(key)), stored);
    assert.equal(instance.records.size, 1);
  });
});

test("body identity must match the transport identity", async () => {
  const instance = createMockErp();
  await withServer(instance, async (base) => {
    const key = "33333333-3333-3333-3333-333333333333:1";
    const id = "33333333-3333-3333-3333-333333333333";
    const mismatched = await postExport(base, key, id, exportPayload("other:1", id));
    assert.equal(mismatched.status, 409);
    assert.equal((await mismatched.json()).error, "IDEMPOTENCY_KEY_MISMATCH");
    assert.equal(instance.records.size, 0);
  });
});

test("status inquiry returns the stored result and 404 for an unknown key", async () => {
  const instance = createMockErp();
  await withServer(instance, async (base) => {
    const key = "44444444-4444-4444-4444-444444444444:1";
    const id = "44444444-4444-4444-4444-444444444444";
    const created = await (await postExport(base, key, id)).json();

    const inquiry = await fetch(`${base}/api/payment-exports/${encodeURIComponent(key)}`);
    assert.equal(inquiry.status, 200);
    assert.deepEqual(await inquiry.json(), created);

    const unknown = await fetch(`${base}/api/payment-exports/${encodeURIComponent("missing:1")}`);
    assert.equal(unknown.status, 404);
  });
});

test("a lost HTTP response converges through the same-key inquiry without a duplicate", async () => {
  const instance = createMockErp();
  instance.setDropResponse(true);
  await withServer(instance, async (base) => {
    const key = "55555555-5555-5555-5555-555555555555:1";
    const id = "55555555-5555-5555-5555-555555555555";

    await assert.rejects(postExport(base, key, id));
    assert.equal(instance.records.size, 1);
    const stored = instance.record(key);

    instance.setDropResponse(false);
    const inquiry = await fetch(`${base}/api/payment-exports/${encodeURIComponent(key)}`);
    assert.equal(inquiry.status, 200);
    assert.equal((await inquiry.json()).externalEventId, stored.externalEventId);

    const replay = await postExport(base, key, id);
    assert.equal(replay.status, 200);
    assert.equal((await replay.json()).externalEventId, stored.externalEventId);
    assert.equal(instance.records.size, 1);
  });
});

test("a processed export emits one signed follow-up webhook", async () => {
  const secret = "test-webhook-secret";
  const received = [];
  const receiver = http.createServer((request, response) => {
    let body = "";
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => {
      received.push({ headers: request.headers, body });
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ status: "APPLIED" }));
    });
  });
  await new Promise((resolve) => receiver.listen(0, "127.0.0.1", resolve));
  const webhookUrl = `http://127.0.0.1:${receiver.address().port}/webhooks/mock-erp/payment-results`;

  const instance = createMockErp({ webhookUrl, webhookSecret: secret });

  try {
    await withServer(instance, async (base) => {
      const key = "66666666-6666-6666-6666-666666666666:1";
      const id = "66666666-6666-6666-6666-666666666666";
      await postExport(base, key, id);

      for (let i = 0; i < 50 && received.length === 0; i++) {
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      assert.equal(received.length, 1);
      const delivery = received[0];
      const timestamp = delivery.headers["x-mock-erp-timestamp"];
      assert.equal(
        delivery.headers["x-mock-erp-signature"],
        signWebhook(secret, timestamp, delivery.body),
      );
      const payload = JSON.parse(delivery.body);
      assert.equal(payload.provider, PROVIDER);
      assert.equal(payload.outcome, "ACKNOWLEDGED");
      assert.equal(payload.externalPaymentKey, key);
      assert.equal(payload.paymentRequestId, id);
      assert.ok(payload.externalEventId);
    });
  } finally {
    await new Promise((resolve) => receiver.close(resolve));
  }
});
