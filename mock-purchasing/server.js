const http = require("node:http");

// Deterministic fixtures for the external purchasing system of record. The
// service is read-only: it exposes a health probe and a versioned purchase
// order aggregate (header, lines, receipts and receipt lines). The aggregate
// snapshotVersion increases whenever any included external fact changes.

const PURCHASE_ORDERS = Object.freeze({
  "PO-1001": Object.freeze({
    snapshotVersion: 5,
    purchaseOrder: Object.freeze({
      purchaseOrderId: "PO-1001",
      status: "CONFIRMED",
      version: 3,
      supplier: Object.freeze({
        supplierId: "SUP-1",
        name: "Hanul Office Supply",
      }),
      lines: Object.freeze([
        Object.freeze({
          purchaseOrderLineId: "POL-1001-1",
          itemId: "ITEM-A4-80",
          itemName: "Premium Copy Paper A4 80g",
          orderedQuantity: 100,
          unitPrice: 2500,
        }),
        Object.freeze({
          purchaseOrderLineId: "POL-1001-2",
          itemId: "ITEM-TONER-BK",
          itemName: "Laser Toner Black",
          orderedQuantity: 20,
          unitPrice: 55000,
        }),
      ]),
    }),
    receipts: Object.freeze([
      Object.freeze({
        receiptId: "RCV-1001-1",
        status: "CONFIRMED",
        receiptDate: "2026-01-05",
        version: 2,
        lines: Object.freeze([
          Object.freeze({
            receiptLineId: "RCL-1001-1-1",
            version: 2,
            purchaseOrderLineId: "POL-1001-1",
            confirmedQuantity: 60,
          }),
          Object.freeze({
            receiptLineId: "RCL-1001-1-2",
            version: 2,
            purchaseOrderLineId: "POL-1001-2",
            confirmedQuantity: 20,
          }),
        ]),
      }),
    ]),
  }),
  "PO-1002": Object.freeze({
    snapshotVersion: 1,
    purchaseOrder: Object.freeze({
      purchaseOrderId: "PO-1002",
      status: "UNCONFIRMED",
      version: 1,
      supplier: Object.freeze({
        supplierId: "SUP-2",
        name: "Daehan Packaging",
      }),
      lines: Object.freeze([
        Object.freeze({
          purchaseOrderLineId: "POL-1002-1",
          itemId: "ITEM-BOX-S",
          itemName: "Shipping Box Small",
          orderedQuantity: 10,
          unitPrice: 1000,
        }),
      ]),
    }),
    receipts: Object.freeze([]),
  }),
});

const AGGREGATE_PATH = /^\/api\/purchase-orders\/([^/]+)$/;

function send(response, status, payload) {
  response.writeHead(status);
  response.end(JSON.stringify(payload));
}

function handler(request, response) {
  response.setHeader("content-type", "application/json; charset=utf-8");
  const pathname = new URL(request.url, "http://localhost").pathname;
  const isKnownPath = pathname === "/health" || AGGREGATE_PATH.test(pathname);

  if (request.method !== "GET") {
    send(response, isKnownPath ? 405 : 404, isKnownPath
      ? { error: "METHOD_NOT_ALLOWED", method: request.method }
      : { error: "Not found" });
    return;
  }

  if (pathname === "/health") {
    send(response, 200, { status: "UP" });
    return;
  }

  const match = AGGREGATE_PATH.exec(pathname);
  if (match) {
    const purchaseOrderId = decodeURIComponent(match[1]);
    const aggregate = PURCHASE_ORDERS[purchaseOrderId];
    if (aggregate) {
      send(response, 200, aggregate);
    } else {
      send(response, 404, { error: "PURCHASE_ORDER_NOT_FOUND", purchaseOrderId });
    }
    return;
  }

  send(response, 404, { error: "Not found" });
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8082);
  http.createServer(handler).listen(port, "0.0.0.0");
}

module.exports = { handler, PURCHASE_ORDERS };
