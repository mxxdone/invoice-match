const assert = require("node:assert/strict");
const http = require("node:http");
const test = require("node:test");
const { handler } = require("./server");

async function withServer(run) {
  const server = http.createServer(handler);
  const port = await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server.address().port));
  });
  try {
    await run(`http://127.0.0.1:${port}`);
  } finally {
    server.close();
  }
}

test("health is deterministic", async () => {
  await withServer(async (base) => {
    const health = await fetch(`${base}/health`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: "UP" });
  });
});

test("confirmed fixture exposes a versioned aggregate with partial receipt", async () => {
  await withServer(async (base) => {
    const response = await fetch(`${base}/api/purchase-orders/PO-1001`);
    assert.equal(response.status, 200);
    const body = await response.json();

    assert.equal(body.snapshotVersion, 5);
    assert.equal(body.purchaseOrder.purchaseOrderId, "PO-1001");
    assert.equal(body.purchaseOrder.status, "CONFIRMED");
    assert.equal(body.purchaseOrder.version, 3);
    assert.equal(body.purchaseOrder.supplier.supplierId, "SUP-1");
    assert.equal(body.purchaseOrder.lines.length, 2);
    assert.deepEqual(body.purchaseOrder.lines[0], {
      purchaseOrderLineId: "POL-1001-1",
      itemId: "ITEM-A4-80",
      itemName: "Premium Copy Paper A4 80g",
      orderedQuantity: 100,
      unitPrice: 2500,
    });

    assert.equal(body.receipts.length, 1);
    assert.equal(body.receipts[0].status, "CONFIRMED");
    assert.equal(body.receipts[0].receiptDate, "2026-01-05");
    assert.equal(body.receipts[0].version, 2);
    assert.deepEqual(body.receipts[0].lines[0], {
      receiptLineId: "RCL-1001-1-1",
      version: 2,
      purchaseOrderLineId: "POL-1001-1",
      confirmedQuantity: 60,
    });
  });
});

test("unconfirmed fixture is exposed and distinguishable", async () => {
  await withServer(async (base) => {
    const response = await fetch(`${base}/api/purchase-orders/PO-1002`);
    assert.equal(response.status, 200);
    const body = await response.json();

    assert.equal(body.snapshotVersion, 1);
    assert.equal(body.purchaseOrder.status, "UNCONFIRMED");
    assert.deepEqual(body.receipts, []);
  });
});

test("ambiguous-item fixture exposes one item id on two active lines", async () => {
  await withServer(async (base) => {
    const response = await fetch(`${base}/api/purchase-orders/PO-1003`);
    assert.equal(response.status, 200);
    const body = await response.json();

    assert.equal(body.purchaseOrder.status, "CONFIRMED");
    const itemIds = body.purchaseOrder.lines.map((line) => line.itemId);
    assert.deepEqual(itemIds, ["ITEM-DUP-1", "ITEM-DUP-1"]);
    assert.equal(itemIds.filter((itemId) => itemId === "ITEM-DUP-1").length, 2);
  });
});

test("unknown purchase order returns 404 with a distinguishable body", async () => {
  await withServer(async (base) => {
    const response = await fetch(`${base}/api/purchase-orders/PO-9999`);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), {
      error: "PURCHASE_ORDER_NOT_FOUND",
      purchaseOrderId: "PO-9999",
    });
  });
});

test("the service is read-only and rejects non-GET methods", async () => {
  await withServer(async (base) => {
    for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
      const response = await fetch(`${base}/api/purchase-orders/PO-1001`, { method });
      assert.equal(response.status, 405, `${method} must be rejected`);
      assert.deepEqual(await response.json(), { error: "METHOD_NOT_ALLOWED", method });
    }

    const missing = await fetch(`${base}/payments`);
    assert.equal(missing.status, 404);
  });
});
