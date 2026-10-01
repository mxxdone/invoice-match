import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { startErpFixture } from '../../scripts/lib/erp-fixture.mjs';

function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

function postExport(baseUrl, key, id) {
  return fetch(`${baseUrl}/api/payment-exports`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'idempotency-key': key,
      'x-payment-request-id': id,
    },
    body: JSON.stringify({ idempotencyKey: key, paymentRequestId: id }),
  });
}

test('a completed export is recorded with the real response status', async () => {
  const port = await freePort();
  const fixture = await startErpFixture({ port, autoWebhook: false, webhookSecret: 'x' });
  try {
    const response = await postExport(fixture.baseUrl, 'k-complete', '11111111-1111-1111-1111-111111111111');
    assert.equal(response.status, 200);
    const record = fixture.exportCompletions()[0];
    assert.equal(record.finished, true);
    assert.equal(record.status, 200);
    assert.ok(record.completedAt >= record.requestedAt);
    assert.equal(fixture.lastCompletedExport().status, 200);
  } finally {
    await fixture.stop();
  }
});

test('a delayed export is open before the response and completed 2xx after', async () => {
  const port = await freePort();
  const fixture = await startErpFixture({ port, autoWebhook: false, webhookSecret: 'x' });
  try {
    fixture.setDelayMs(300);
    const pending = postExport(fixture.baseUrl, 'k-delay', '22222222-2222-2222-2222-222222222222');
    await new Promise((resolve) => setTimeout(resolve, 60));
    const open = fixture.exportCompletions()[0];
    assert.ok(open, 'the request must be observed');
    assert.equal(open.finished, false);
    assert.equal(open.closedWithoutFinish, false);
    assert.equal(fixture.lastCompletedExport(), null);

    const response = await pending;
    assert.equal(response.status, 200);
    const done = fixture.exportCompletions()[0];
    assert.equal(done.finished, true);
    assert.equal(done.status, 200);
    assert.equal(fixture.lastCompletedExport().status, 200);
  } finally {
    await fixture.stop();
  }
});

test('a dropped export is closed without a finish and never a completed 2xx', async () => {
  const port = await freePort();
  const fixture = await startErpFixture({ port, autoWebhook: false, webhookSecret: 'x', dropResponse: true });
  try {
    await assert.rejects(
      postExport(fixture.baseUrl, 'k-drop', '33333333-3333-3333-3333-333333333333'),
      'the dropped response must fail the client',
    );
    const record = fixture.exportCompletions()[0];
    assert.ok(record, 'the request must be observed');
    assert.equal(record.finished, false);
    assert.equal(record.status, null);
    assert.equal(record.closedWithoutFinish, true);
    assert.equal(fixture.lastCompletedExport(), null);
  } finally {
    await fixture.stop();
  }
});
