import test from 'node:test';
import assert from 'node:assert/strict';
import { buildInvoiceCaseQuery } from '../src/app/api/query.ts';

test('the query always carries the server page and size', () => {
  assert.deepEqual(
    buildInvoiceCaseQuery({ status: 'all', supplierId: null, submittedBy: null, searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20 }),
    { page: '0', size: '20' },
  );
});

test('an active status tab becomes the server status filter and "all" is omitted', () => {
  const query = buildInvoiceCaseQuery({ status: 'REVIEW_PENDING', supplierId: null, submittedBy: null, searchField: 'invoiceNumber', searchValue: null, page: 2, size: 50 });
  assert.equal(query.status, 'REVIEW_PENDING');
  assert.equal(query.page, '2');
  assert.equal(query.size, '50');
  assert.equal('status' in buildInvoiceCaseQuery({ status: 'all', supplierId: null, submittedBy: null, searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20 }), false);
});

test('supplier and submitter are exact-match server filters, blank ones omitted', () => {
  const query = buildInvoiceCaseQuery({ status: null, supplierId: 'SUP-1001', submittedBy: 'submitter-02', searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20 });
  assert.equal(query.supplierId, 'SUP-1001');
  assert.equal(query.submittedBy, 'submitter-02');
  const blank = buildInvoiceCaseQuery({ status: null, supplierId: '  ', submittedBy: '', searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20 });
  assert.equal('supplierId' in blank, false);
  assert.equal('submittedBy' in blank, false);
});

test('partial search text is sent verbatim to the chosen field without client normalization', () => {
  const invoice = buildInvoiceCaseQuery({ status: null, supplierId: null, submittedBy: null, submittedFrom: null, submittedTo: null, searchField: 'invoiceNumber', searchValue: 'inv 2026 01', page: 0, size: 20 });
  assert.equal(invoice.invoiceNumber, 'inv 2026 01');
  assert.equal('purchaseOrderId' in invoice, false);
  const po = buildInvoiceCaseQuery({ status: null, supplierId: null, submittedBy: null, submittedFrom: null, submittedTo: null, searchField: 'purchaseOrderId', searchValue: 'po-1001', page: 0, size: 20 });
  assert.equal(po.purchaseOrderId, 'po-1001');
  assert.equal('invoiceNumber' in po, false);
});

test('a resolved KST instant range is forwarded as inclusive server bounds', () => {
  const query = buildInvoiceCaseQuery({ status: null, supplierId: null, submittedBy: null, submittedFrom: '2026-09-30T00:00:00.000000+09:00', submittedTo: '2026-09-30T23:59:59.999999+09:00', searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20 });
  assert.equal(query.submittedFrom, '2026-09-30T00:00:00.000000+09:00');
  assert.equal(query.submittedTo, '2026-09-30T23:59:59.999999+09:00');
  const none = buildInvoiceCaseQuery({ status: null, supplierId: null, submittedBy: null, submittedFrom: null, submittedTo: null, searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20 });
  assert.equal('submittedFrom' in none, false);
  assert.equal('submittedTo' in none, false);
});
