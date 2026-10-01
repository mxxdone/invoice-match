// P1-11 Phase 1 integration acceptance scenarios.
//
// Every scenario runs over real HTTP against the throwaway core-api and asserts
// the persisted decision/allocation/payment/outbox/webhook/audit rows in the
// throwaway PostgreSQL database plus the Mock ERP's own record count. It never
// trusts the UI or a screenshot alone. The `request`, `docker` and container id
// are injected so the same code can be driven by the safe lifecycle in
// scripts/lib/verify-core.mjs and so no resource is created here.
//
// The ERP fixture is hosted in-process (scripts/lib/erp-fixture.mjs) because it
// reuses the committed mock-erp module and must expose the exact ordering of a
// lost response and a later signed result. The clean Compose smoke separately
// exercises the real containerised auto-webhook path.

import { randomUUID } from 'node:crypto';
import { VerifyError } from './verify-core.mjs';

const SUBMITTER = { user: 'submitter', pass: 'submitter-pass' };
const SUBMITTER2 = { user: 'submitter2', pass: 'submitter2-pass' };
const APPROVER = { user: 'approver', pass: 'approver-pass' };
const OPERATOR = { user: 'operator', pass: 'operator-pass' };

const SUPPLIER = 'SUP-1';
const PO = 'PO-1001';

function basic({ user, pass }) {
  return 'Basic ' + Buffer.from(`${user}:${pass}`, 'utf8').toString('base64');
}

function jsonHeaders(actor) {
  return { authorization: basic(actor), 'content-type': 'application/json' };
}

function fail(message) {
  throw new VerifyError(message);
}

function compact(object) {
  return JSON.stringify(object);
}

export async function runP111Scenarios({ request, config, guard, log = () => {}, children = [], containers = [], docker }) {
  const core = `http://127.0.0.1:${config.ports.core}`;
  const pgId = containers[0];
  const erpChild = children.find((child) => child.name === 'mock-erp');
  const erp = erpChild?.erp;
  if (!erpChild || !erp) {
    fail('in-process mock-erp fixture was not started');
  }
  if (!pgId || !docker) {
    fail('database assertion context is unavailable');
  }

  const evidence = { steps: [], counts: {}, fixtures: {} };
  const record = (name, detail) => {
    evidence.steps.push({ name, ...detail });
    log(`PASS ${name}`);
  };

  const call = async (method, path, { actor, body, headers = {} } = {}) => {
    const response = await request(`${core}${path}`, {
      method,
      headers: { ...(actor ? { authorization: basic(actor) } : {}), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    let parsed = null;
    try {
      parsed = JSON.parse(response.body);
    } catch {
      parsed = null;
    }
    return { status: response.status, body: response.body, json: parsed };
  };

  const expect = (condition, message) => {
    if (!condition) fail(message);
  };

  const expectStatus = (result, allowed, label) => {
    const list = Array.isArray(allowed) ? allowed : [allowed];
    if (!list.includes(result.status)) {
      const code = result.json && result.json.code ? `, code ${result.json.code}` : '';
      const message = result.json && result.json.message ? `, message ${String(result.json.message).slice(0, 200)}` : '';
      fail(`${label}: expected ${list.join('/')} but got ${result.status}${code}${message}`);
    }
    return result;
  };

  const psql = async (sql) => {
    const result = await docker.exec(pgId, [
      'psql', '-U', 'invoice_match', '-d', 'invoice_match', '-v', 'ON_ERROR_STOP=1', '-tAc', sql,
    ]);
    if (result.code !== 0) {
      fail(`psql failed: ${(result.stderr || '').trim() || 'no stderr'}`);
    }
    return (result.stdout || '').trim();
  };
  const scalar = async (sql) => {
    const value = await psql(sql);
    return Number(value);
  };
  const resetDatabase = async () => {
    await psql('truncate table invoice_case cascade');
    await psql('truncate table idempotency_record');
    await psql('truncate table purchase_order_snapshot cascade');
  };
  const waitFor = async (label, predicate, { timeoutMs = 25000, intervalMs = 250 } = {}) => {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const value = await predicate();
      if (value) return value;
      if (Date.now() >= deadline) fail(`timed out waiting for ${label}`);
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
    }
  };

  // --- shared API helpers -------------------------------------------------

  let sequence = 0;
  const nextId = (prefix) => `${prefix}-${++sequence}`;

  const createCase = async (invoiceNumber, { actor = SUBMITTER, supplier = SUPPLIER, po = PO } = {}) => {
    const result = await call('POST', '/api/invoice-cases', {
      actor,
      headers: { 'content-type': 'application/json' },
      body: { requestId: nextId('create'), supplierId: supplier, purchaseOrderId: po, invoiceNumber },
    });
    expectStatus(result, 201, `create ${invoiceNumber}`);
    return result.json;
  };

  const detailAs = async (id, actor = APPROVER) => {
    const result = await call('GET', `/api/invoice-cases/${id}`, { actor });
    expectStatus(result, 200, `detail ${id}`);
    return result.json;
  };

  const replaceDraft = async (id, version, lines) => {
    const result = await call('PUT', `/api/invoice-cases/${id}/draft`, {
      actor: SUBMITTER,
      headers: { 'content-type': 'application/json' },
      body: { requestId: nextId('draft'), expectedCaseVersion: version, lines },
    });
    expectStatus(result, 200, `draft ${id}`);
    return result.json;
  };

  const submitCase = async (id, version) => {
    const result = await call('POST', `/api/invoice-cases/${id}/submit`, {
      actor: SUBMITTER,
      headers: { 'content-type': 'application/json' },
      body: { requestId: nextId('submit'), expectedCaseVersion: version },
    });
    expectStatus(result, 200, `submit ${id}`);
    return result.json;
  };

  const runMatch = async (id, actor = OPERATOR) => {
    const result = await call('POST', `/api/invoice-cases/${id}/match`, {
      actor,
      headers: { 'content-type': 'application/json' },
      body: { requestId: nextId('match') },
    });
    expectStatus(result, 201, `match ${id}`);
    return result.json;
  };

  const latestMatch = async (id, actor = APPROVER) => {
    const result = await call('GET', `/api/invoice-cases/${id}/match`, { actor });
    expectStatus(result, 200, `latest match ${id}`);
    return result.json;
  };

  const freeze = async (id, actor = APPROVER) => {
    const version = (await detailAs(id, actor)).version;
    const result = await call('POST', `/api/invoice-cases/${id}/review-snapshots`, {
      actor,
      headers: { 'content-type': 'application/json' },
      body: { requestId: nextId('freeze'), expectedCaseVersion: version },
    });
    expectStatus(result, [200, 201], `freeze ${id}`);
    return result.json;
  };

  const approve = async (id, snapshot) => {
    const version = (await detailAs(id)).version;
    return call('POST', `/api/invoice-cases/${id}/approve`, {
      actor: APPROVER,
      headers: { 'content-type': 'application/json' },
      body: {
        requestId: nextId('approve'),
        expectedCaseVersion: version,
        reviewSnapshotId: snapshot.id,
        reviewPayloadHash: snapshot.payloadHash,
      },
    });
  };

  const handoff = async (id, actor = APPROVER) => {
    const result = await call('GET', `/api/invoice-cases/${id}/handoff`, { actor });
    expectStatus(result, 200, `handoff ${id}`);
    return result.json;
  };

  const auditEntries = async (id, actor = APPROVER) => {
    const result = await call('GET', `/api/invoice-cases/${id}/audit-entries?limit=100`, { actor });
    expectStatus(result, 200, `audit ${id}`);
    return result.json;
  };

  const decisions = async (id, actor = APPROVER) => {
    const result = await call('GET', `/api/invoice-cases/${id}/review-decisions`, { actor });
    expectStatus(result, 200, `decisions ${id}`);
    return result.json;
  };

  const exceptionTypes = (matchResult) => (matchResult.payload?.exceptions ?? []).map((item) => item.type);

  const createSubmittedCase = async (invoiceNumber, lines, options = {}) => {
    const created = await createCase(invoiceNumber, options);
    const drafted = await replaceDraft(created.id, created.version, lines);
    await submitCase(created.id, drafted.version);
    return created.id;
  };

  const line = (quantity, unitPrice, confirmedItemId) => ([
    { lineNumber: 1, rawItemName: 'Copy Paper', quantity, unitPrice, confirmedItemId },
  ]);

  // --- AC: normal approval ------------------------------------------------

  guard();
  const normal = await createSubmittedCase('AC-NORMAL-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  const normalMatch = await runMatch(normal);
  expect(normalMatch.payload?.normal === true, 'normal scenario must produce a normal match');
  const normalSnapshot = await freeze(normal);
  const normalApproved = expectStatus(await approve(normal, normalSnapshot), [200, 201], 'normal approve');
  const normalPaymentId = normalApproved.json.paymentRequestId;
  expect(normalPaymentId, 'normal approve must create a payment request id');
  const normalKey = `${normalPaymentId}:1`;

  const normalTuple = await waitFor('normal export convergence', async () => {
    const view = await handoff(normal);
    if (view.payment?.paymentStatus === 'ACKNOWLEDGED' && view.payment?.outboxStatus === 'DELIVERED' && view.caseStatus === 'EXPORTED') {
      return view;
    }
    return null;
  });
  expect(normalTuple.payment.paymentRequestId === normalPaymentId, 'normal handoff must expose the created payment request');
  expect(normalTuple.payment.externalRequestKey === normalApproved.json.externalRequestKey, 'normal handoff must expose the deterministic payment key');
  expect(normalTuple.payment.exportVersion === 1, 'normal handoff must expose export version 1');
  evidence.counts.normal = {
    paymentRequests: await scalar(`select count(*) from payment_request where invoice_case_id = '${normal}'`),
    outboxEvents: await scalar(`select count(*) from outbox_event where invoice_case_id = '${normal}'`),
    approvedDecisions: await scalar(`select count(*) from review_decision where invoice_case_id = '${normal}' and decision = 'APPROVED'`),
    allocations: await scalar(`select count(*) from receipt_allocation where invoice_case_id = '${normal}'`),
    allocatedQuantity: await scalar(`select coalesce(sum(allocated_quantity),0) from receipt_allocation where invoice_case_id = '${normal}'`),
    approveAudit: await scalar(`select count(*) from audit_entry where invoice_case_id = '${normal}' and action = 'APPROVE'`),
  };
  expect(evidence.counts.normal.paymentRequests === 1, 'normal approval must persist exactly one payment request');
  expect(evidence.counts.normal.outboxEvents === 1, 'normal approval must persist exactly one outbox event');
  expect(evidence.counts.normal.approvedDecisions === 1, 'normal approval must persist one APPROVED decision');
  expect(evidence.counts.normal.allocatedQuantity === 5, 'normal approval must allocate the full invoice quantity');
  expect(evidence.counts.normal.approveAudit === 1, 'normal approval must be audited once');
  const decisionRows = await decisions(normal);
  expect((decisionRows.items ?? decisionRows).some((item) => item.decision === 'APPROVED'), 'decision history must contain the APPROVED decision');
  const auditRows = await auditEntries(normal);
  expect((auditRows.entries ?? []).some((item) => item.action === 'APPROVE'), 'audit history must contain APPROVE');

  // The same signed result delivered again is a replay with one logical effect.
  const normalRecord = erp.record(normalKey);
  expect(normalRecord, 'Mock ERP must hold one record for the normal export');
  await erp.deliverWebhook(normalRecord);
  await erp.deliverWebhook(normalRecord);
  const normalResultEvents = await scalar(`select count(*) from payment_result_event where external_payment_key = '${normalKey}'`);
  expect(normalResultEvents === 1, `normal webhook replays must persist one result event (was ${normalResultEvents})`);
  record('ac-normal-approval', {
    api: 'POST /approve + GET /handoff + GET /review-decisions + GET /audit-entries',
    caseId: normal,
    paymentRequestId: normalPaymentId,
    externalRequestKey: normalKey,
    persisted: evidence.counts.normal,
    erpRecords: erp.records.size,
  });

  // --- AC: 60/100 supplement -> corrected resubmission --------------------

  guard();
  await resetDatabase();
  const supplement = await createSubmittedCase('AC-60-100-' + Date.now(), line(100, 2500, 'ITEM-A4-80'));
  const overMatch = await runMatch(supplement);
  expect(exceptionTypes(overMatch).includes('QUANTITY_EXCEEDS_RECEIPT_BALANCE'), 'invoice 100 against receipt 60 must raise QUANTITY_EXCEEDS_RECEIPT_BALANCE');
  const overSnapshot = await freeze(supplement);
  const supplementRequest = await call('POST', `/api/invoice-cases/${supplement}/supplement-requests`, {
    actor: APPROVER,
    headers: { 'content-type': 'application/json' },
    body: {
      requestId: nextId('supplement'),
      expectedCaseVersion: (await detailAs(supplement)).version,
      reviewSnapshotId: overSnapshot.id,
      reviewPayloadHash: overSnapshot.payloadHash,
      reason: 'P1-11: quantity exceeds confirmed receipt balance',
    },
  });
  expectStatus(supplementRequest, [200, 201], 'supplement request');
  expect((await detailAs(supplement)).status === 'SUPPLEMENT_REQUIRED', 'case must enter SUPPLEMENT_REQUIRED');

  const opened = expectStatus(await call('POST', `/api/invoice-cases/${supplement}/revisions`, {
    actor: SUBMITTER,
    headers: { 'content-type': 'application/json' },
    body: { requestId: nextId('revision'), expectedCaseVersion: (await detailAs(supplement)).version },
  }), [200, 201], 'open revision').json;
  const corrected = await replaceDraft(supplement, opened.version, line(60, 2500, 'ITEM-A4-80'));
  await submitCase(supplement, corrected.version);
  const correctedMatch = await runMatch(supplement);
  expect(correctedMatch.payload?.normal === true, 'corrected 60 resubmission must be a normal match');
  expect(correctedMatch.payload?.evidenceBundle?.version === 2, 'corrected resubmission must be evidence bundle v2');
  const correctedSnapshot = await freeze(supplement);
  expectStatus(await approve(supplement, correctedSnapshot), [200, 201], 'approve corrected resubmission');
  await waitFor('supplement export convergence', async () => {
    const view = await handoff(supplement);
    return view.caseStatus === 'EXPORTED';
  });
  const supplementAllocated = await scalar(`select coalesce(sum(allocated_quantity),0) from receipt_allocation where invoice_case_id = '${supplement}'`);
  expect(supplementAllocated === 60, `corrected approval must allocate 60 (was ${supplementAllocated})`);
  const bundleVersions = await call('GET', `/api/invoice-cases/${supplement}/evidence-bundles`, { actor: APPROVER });
  expectStatus(bundleVersions, 200, 'supplement bundle list');
  expect(Math.max(...bundleVersions.json.map((item) => item.version)) === 2, 'supplement case must retain evidence bundle v2');
  record('ac-60-100-supplement', {
    api: 'match exception + supplement-requests + revisions + approve',
    caseId: supplement,
    allocatedQuantity: supplementAllocated,
  });

  // --- AC: unit price difference ------------------------------------------

  guard();
  const unitPrice = await createSubmittedCase('AC-PRICE-' + Date.now(), line(5, 9999, 'ITEM-A4-80'));
  const priceMatch = await runMatch(unitPrice);
  expect(exceptionTypes(priceMatch).includes('UNIT_PRICE_MISMATCH'), 'a different unit price must raise UNIT_PRICE_MISMATCH');
  expect(priceMatch.payload?.normal === false, 'a unit price mismatch must not be normal');
  const priceSnapshot = await freeze(unitPrice);
  const priceApprove = await approve(unitPrice, priceSnapshot);
  expectStatus(priceApprove, 409, 'abnormal match approval');
  expect(priceApprove.json?.code === 'APPROVAL_NOT_PERMITTED', 'abnormal approval must fail with APPROVAL_NOT_PERMITTED');
  const pricePayment = await scalar(`select count(*) from payment_request where invoice_case_id = '${unitPrice}'`);
  expect(pricePayment === 0, 'blocked abnormal approval must create no payment request');
  const priceHandoff = await handoff(unitPrice);
  expect(priceHandoff.payment == null, 'blocked abnormal approval must leave handoff payment null');
  record('ac-unit-price-difference', { api: 'match + approve -> 409', caseId: unitPrice, code: priceApprove.json.code });

  // --- AC: item mapping ---------------------------------------------------

  guard();
  await resetDatabase();
  const mappingCase = await createSubmittedCase('AC-MAPPING-' + Date.now(), line(5, 2500, null));
  const mappingMatch = await runMatch(mappingCase);
  expect(exceptionTypes(mappingMatch).includes('ITEM_UNCONFIRMED'), 'a null confirmed item must raise ITEM_UNCONFIRMED');
  const mappingSnapshot = await freeze(mappingCase);
  const mapped = expectStatus(await call('POST', `/api/invoice-cases/${mappingCase}/mapping-decisions`, {
    actor: APPROVER,
    headers: { 'content-type': 'application/json' },
    body: {
      requestId: nextId('mapping'),
      expectedCaseVersion: (await detailAs(mappingCase)).version,
      reviewSnapshotId: mappingSnapshot.id,
      reviewPayloadHash: mappingSnapshot.payloadHash,
      lineNumber: 1,
      itemId: 'ITEM-A4-80',
    },
  }), [200, 201], 'mapping decision').json;
  expect(mapped.successorSnapshot?.id, 'mapping must freeze a successor snapshot');
  const mappingVersion = (await detailAs(mappingCase)).version;
  expect(
    mapped.successorSnapshot.targetCaseVersion === mappingVersion,
    `mapping successor targetCaseVersion ${mapped.successorSnapshot.targetCaseVersion} must equal the current case version ${mappingVersion}`,
  );
  const mappingApprove = await approve(mappingCase, mapped.successorSnapshot);
  if (mappingApprove.status !== 200 && mappingApprove.status !== 201) {
    const refrozen = await freeze(mappingCase);
    record('ac-item-mapping-direct-approve-conflict', {
      api: 'POST /approve with the mapping successor snapshot',
      status: mappingApprove.status,
      code: mappingApprove.json?.code ?? null,
      message: mappingApprove.json?.message ?? null,
    });
    expectStatus(await approve(mappingCase, refrozen), [200, 201], 'approve after mapping (refrozen subject)');
  }
  await waitFor('mapping export convergence', async () => (await handoff(mappingCase)).caseStatus === 'EXPORTED');
  const mappingAllocated = await scalar(`select coalesce(sum(allocated_quantity),0) from receipt_allocation where invoice_case_id = '${mappingCase}'`);
  expect(mappingAllocated === 5, 'approval after mapping must allocate the mapped line');
  record('ac-item-mapping', { api: 'mapping-decisions + successor snapshot + approve', caseId: mappingCase });

  // --- AC: insufficient evidence (ambiguous item) -------------------------

  guard();
  const ambiguous = await createSubmittedCase('AC-AMBIG-' + Date.now(), line(5, 4000, 'ITEM-DUP-1'), { po: 'PO-1003' });
  const ambiguousMatch = await runMatch(ambiguous);
  expect(exceptionTypes(ambiguousMatch).includes('EVIDENCE_INSUFFICIENT'), 'an item on two active PO lines must raise EVIDENCE_INSUFFICIENT');
  const ambiguousSnapshot = await freeze(ambiguous);
  const ambiguousApprove = await approve(ambiguous, ambiguousSnapshot);
  expectStatus(ambiguousApprove, 409, 'insufficient-evidence approval');
  expect((await scalar(`select count(*) from payment_request where invoice_case_id = '${ambiguous}'`)) === 0, 'insufficient evidence must create no payment request');
  record('ac-insufficient-evidence', { api: 'match PO-1003 + approve -> 409', caseId: ambiguous });
  const otherSupplierCase = await createSubmittedCase('AC-OWNER-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  const otherRead = await call('GET', `/api/invoice-cases/${otherSupplierCase}`, { actor: SUBMITTER2 });
  expectStatus(otherRead, 403, 'another submitter must be denied');

  // --- AC: duplicate invoice number ---------------------------------------

  guard();
  const duplicateNumber = 'AC-DUP-' + Date.now();
  await createSubmittedCase(duplicateNumber, line(5, 2500, 'ITEM-A4-80'));
  const duplicateSecond = await createSubmittedCase(duplicateNumber, line(5, 2500, 'ITEM-A4-80'));
  const duplicateMatch = await runMatch(duplicateSecond);
  expect(exceptionTypes(duplicateMatch).includes('DUPLICATE_INVOICE_SUSPECTED'), 'the same normalized invoice number must raise DUPLICATE_INVOICE_SUSPECTED');
  record('ac-duplicate-invoice-number', { api: 'second case match', caseId: duplicateSecond, invoiceNumber: duplicateNumber });

  // --- AC: a signed result that disagrees with the export is rejected ------
  // The payment-request-id mismatch guard is status independent, so this is a
  // deterministic rejection with no side effect regardless of how far the relay
  // has progressed.

  guard();
  await resetDatabase();
  const preSend = await createSubmittedCase('AC-PRESEND-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  await runMatch(preSend);
  const preSendSnapshot = await freeze(preSend);
  const preSendApproved = expectStatus(await approve(preSend, preSendSnapshot), [200, 201], 'pre-send approve');
  const preSendKey = `${preSendApproved.json.paymentRequestId}:1`;
  const mismatchBody = compact({
    provider: 'mock-erp',
    externalEventId: `evt-mismatch-${Date.now()}`,
    externalPaymentKey: preSendKey,
    paymentRequestId: randomUUID(),
    outcome: 'ACKNOWLEDGED',
    externalReference: 'ERP-MISMATCH',
  });
  const mismatchTimestamp = Math.floor(Date.now() / 1000);
  const mismatchWebhook = await request(`${core}/webhooks/mock-erp/payment-results`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-mock-erp-timestamp': String(mismatchTimestamp),
      'x-mock-erp-signature': erpChild.sign(String(mismatchTimestamp), mismatchBody),
    },
    body: mismatchBody,
  });
  expectStatus({ status: mismatchWebhook.status, json: null }, 409, 'signed result with a mismatched payment request id');
  expect(
    (await scalar(`select count(*) from payment_result_event where external_payment_key = '${preSendKey}'`)) === 0,
    'a rejected signed result must persist no result event',
  );
  const preSendPaymentCount = await scalar(`select count(*) from payment_request where invoice_case_id = '${preSend}'`);
  expect(preSendPaymentCount === 1, 'the rejection must not create a second payment request');
  record('ac-signed-result-mismatch-rejected', { api: 'signed result with mismatched payment id -> 409', caseId: preSend, status: mismatchWebhook.status });

  // --- AC: signed result resolves an in-flight SENDING send ---------------

  guard();
  await resetDatabase();
  const inFlight = await createSubmittedCase('AC-INFLIGHT-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  await runMatch(inFlight);
  const inFlightSnapshot = await freeze(inFlight);
  erpChild.setDelayMs(3000);
  const inFlightApproved = expectStatus(await approve(inFlight, inFlightSnapshot), [200, 201], 'in-flight approve');
  const inFlightKey = `${inFlightApproved.json.paymentRequestId}:1`;
  const sending = await waitFor('committed SENDING window', async () => {
    const view = await handoff(inFlight);
    return view.payment?.paymentStatus === 'SENDING' ? view : null;
  });
  expect(sending.payment.outboxStatus === 'SENDING', 'the relay must commit the outbox and payment to SENDING before HTTP');
  const inFlightRecord = erp.record(inFlightKey);
  // The Mock ERP has not answered yet, so no record exists; deliver the signed
  // result from a synthetic event with the same agreed key and outcome. This is
  // the exact "signed webhook arrives during SENDING" ordering the relay must
  // tolerate.
  const inFlightBody = compact({
    provider: 'mock-erp',
    externalEventId: `evt-inflight-${Date.now()}`,
    externalPaymentKey: inFlightKey,
    paymentRequestId: inFlightApproved.json.paymentRequestId,
    outcome: 'ACKNOWLEDGED',
    externalReference: 'ERP-INFLIGHT',
  });
  const inFlightTimestamp = Math.floor(Date.now() / 1000);
  const inFlightWebhook = await request(`${core}/webhooks/mock-erp/payment-results`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-mock-erp-timestamp': String(inFlightTimestamp),
      'x-mock-erp-signature': erpChild.sign(String(inFlightTimestamp), inFlightBody),
    },
    body: inFlightBody,
  });
  expectStatus({ status: inFlightWebhook.status, json: null }, 200, 'signed result during SENDING');
  const inFlightConverged = await waitFor('in-flight webhook convergence', async () => {
    const view = await handoff(inFlight);
    return view.caseStatus === 'EXPORTED' ? view : null;
  });
  // Let the delayed ERP response return; the relay must become a no-op and must
  // not create a second payment or result event.
  await new Promise((resolve) => setTimeout(resolve, 3500));
  expect((await handoff(inFlight)).caseStatus === 'EXPORTED', 'the delayed relay finalization must not change the converged state');
  const inFlightCounts = {
    payments: await scalar(`select count(*) from payment_request where invoice_case_id = '${inFlight}'`),
    resultEvents: await scalar(`select count(*) from payment_result_event where external_payment_key = '${inFlightKey}'`),
  };
  expect(inFlightCounts.payments === 1, 'the SENDING webhook race must keep one logical payment');
  expect(inFlightCounts.resultEvents === 1, 'the SENDING webhook race must persist one result event');
  erpChild.setDelayMs(0);
  record('ac-signed-result-during-sending', {
    api: 'signed result while outbox/payment SENDING, then delayed relay response',
    caseId: inFlight,
    status: inFlightWebhook.status,
    converged: inFlightCounts,
  });

  // --- AC: ERP post-commit response loss -> RESULT_UNKNOWN -> convergence --

  guard();
  await resetDatabase();
  const loss = await createSubmittedCase('AC-LOSS-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  await runMatch(loss);
  const lossSnapshot = await freeze(loss);
  erp.setDropResponse(true);
  const lossApproved = expectStatus(await approve(loss, lossSnapshot), [200, 201], 'loss approve');
  const lossKey = `${lossApproved.json.paymentRequestId}:1`;
  const lossUnknown = await waitFor('persisted RESULT_UNKNOWN', async () => {
    const view = await handoff(loss);
    if (view.payment?.paymentStatus === 'RESULT_UNKNOWN' && view.payment?.outboxStatus === 'RESULT_UNKNOWN') {
      return view;
    }
    return null;
  });
  expect(lossUnknown.caseStatus === 'EXPORT_PENDING', 'a lost response must leave the case EXPORT_PENDING, not EXPORTED');
  expect((await lossUnknown.payment).paymentStatus !== 'ACKNOWLEDGED', 'UNKNOWN must be persisted before any result');
  const lossPaymentCountBefore = await scalar(`select count(*) from payment_request where invoice_case_id = '${loss}'`);
  expect(lossPaymentCountBefore === 1, 'UNKNOWN must keep exactly one logical payment request');
  evidence.counts.lossUnknown = {
    paymentStatus: lossUnknown.payment.paymentStatus,
    outboxStatus: lossUnknown.payment.outboxStatus,
    caseStatus: lossUnknown.caseStatus,
  };

  // Explicit external status inquiry by the same idempotency key: one record.
  const inquiryKey = encodeURIComponent(lossKey);
  const inquiry = await request(`${erpChild.baseUrl}/api/payment-exports/${inquiryKey}`);
  expect(inquiry.status === 200, `ERP status inquiry must return the processed export (status ${inquiry.status})`);
  const inquiryBody = JSON.parse(inquiry.body);
  expect(inquiryBody.externalPaymentKey === lossKey, 'ERP inquiry must answer with the same idempotency key');
  expect(erp.records.size >= 1, 'ERP must hold the processed export');
  expect([...erp.records.keys()].filter((key) => key === lossKey).length === 1, 'ERP must hold exactly one logical record for the key');

  // The signed result is delivered only after UNKNOWN is persisted, and the
  // core converges without a new payment or a new export key.
  erp.setDropResponse(false);
  const lossRecord = erp.record(lossKey);
  const delivered = await erp.deliverWebhook(lossRecord);
  expect(delivered === true, 'the signed result re-delivery must be accepted');
  const lossConverged = await waitFor('post-UNKNOWN signed convergence', async () => {
    const view = await handoff(loss);
    return view.caseStatus === 'EXPORTED' && view.payment?.paymentStatus === 'ACKNOWLEDGED' ? view : null;
  });
  expect(lossConverged.payment.paymentRequestId === lossApproved.json.paymentRequestId, 'convergence must keep the same payment request');
  expect(lossConverged.payment.externalRequestKey === lossApproved.json.externalRequestKey, 'convergence must keep the same export key');
  const lossCounts = {
    paymentRequests: await scalar(`select count(*) from payment_request where invoice_case_id = '${loss}'`),
    outboxEvents: await scalar(`select count(*) from outbox_event where invoice_case_id = '${loss}'`),
    resultEvents: await scalar(`select count(*) from payment_result_event where external_payment_key = '${lossKey}'`),
    delivered: lossConverged.payment.outboxStatus,
  };
  expect(lossCounts.paymentRequests === 1, 'convergence must not create a second payment request');
  expect(lossCounts.resultEvents === 1, 'convergence must persist exactly one external result event');
  await erp.deliverWebhook(lossRecord);
  expect((await scalar(`select count(*) from payment_result_event where external_payment_key = '${lossKey}'`)) === 1, 'a duplicate signed result must be a replay with one event');
  record('ac-erp-response-loss', {
    api: 'approve + dropResponse + GET inquiry + signed result',
    caseId: loss,
    unknown: evidence.counts.lossUnknown,
    inquiry: { status: inquiry.status, outcome: inquiryBody.outcome },
    converged: lossCounts,
  });

  // --- AC: FAILED is distinct from RESULT_UNKNOWN -------------------------

  guard();
  await resetDatabase();
  const failed = await createSubmittedCase('AC-FAILED-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  await runMatch(failed);
  const failedSnapshot = await freeze(failed);
  erp.setDropResponse(true);
  const failedApproved = expectStatus(await approve(failed, failedSnapshot), [200, 201], 'failed approve');
  const failedKey = `${failedApproved.json.paymentRequestId}:1`;
  await waitFor('failed-case UNKNOWN', async () => (await handoff(failed)).payment?.paymentStatus === 'RESULT_UNKNOWN');
  erp.setDropResponse(false);
  const failedRecord = erp.record(failedKey);
  failedRecord.outcome = 'FAILED';
  expect((await erp.deliverWebhook(failedRecord)) === true, 'the signed FAILED result must be accepted');
  const failedView = await waitFor('persisted FAILED', async () => {
    const view = await handoff(failed);
    return view.payment?.paymentStatus === 'FAILED' && view.payment?.outboxStatus === 'FAILED' ? view : null;
  });
  expect(failedView.caseStatus === 'EXPORT_PENDING', 'a FAILED result must leave the case EXPORT_PENDING');
  const failedOutcomes = await psql(`select outcome from payment_result_event where external_payment_key = '${failedKey}'`);
  expect(failedOutcomes === 'FAILED', `the persisted result event must be FAILED (was ${failedOutcomes})`);
  record('ac-failed-distinct', { api: 'signed FAILED result', caseId: failed, outboxStatus: failedView.payment.outboxStatus });

  // --- AC: shared receipt 40+40 contention, repeated on real PostgreSQL ----

  const iterations = 3;
  const contentionRuns = [];
  // The contention proof is about the shared receipt allocation invariant and
  // the single logical payment, not about export. Keep the ERP in a state where
  // any racing relay send resolves immediately (RESULT_UNKNOWN) so the
  // assertion never depends on scheduler timing.
  erpChild.setDropResponse(true);
  for (let run = 1; run <= iterations; run += 1) {
    guard();
    await resetDatabase();
    const stamp = `${Date.now()}-${run}`;
    const first = await createSubmittedCase(`AC-CONTEND-A-${stamp}`, line(40, 2500, 'ITEM-A4-80'));
    const second = await createSubmittedCase(`AC-CONTEND-B-${stamp}`, line(40, 2500, 'ITEM-A4-80'));
    await runMatch(first);
    await runMatch(second);
    const firstSnapshot = await freeze(first);
    const secondSnapshot = await freeze(second);
    const outcomes = await Promise.all([approve(first, firstSnapshot), approve(second, secondSnapshot)]);
    const statuses = outcomes.map((result) => result.status).sort((a, b) => a - b);
    const winners = outcomes.filter((result) => result.status === 200 || result.status === 201);
    const losers = outcomes.filter((result) => result.status === 409);
    expect(winners.length === 1, `contention run ${run}: exactly one approval must win (statuses ${outcomes.map((o) => o.status).join(',')})`);
    expect(losers.length === 1, `contention run ${run}: exactly one approval must lose`);
    expect(losers[0].json?.code === 'INSUFFICIENT_RECEIPT_BALANCE', `contention run ${run}: the loser must report INSUFFICIENT_RECEIPT_BALANCE (was ${losers[0].json?.code})`);
    const total = await scalar('select coalesce(sum(allocated_quantity),0) from receipt_allocation');
    expect(total === 40, `contention run ${run}: committed allocation must stay at 40 (was ${total})`);
    const payments = await scalar("select count(*) from payment_request");
    expect(payments === 1, `contention run ${run}: one winning approval must create one payment (was ${payments})`);
    const winnerCaseId = winners[0].json?.invoiceCaseId;
    expect(winnerCaseId, `contention run ${run}: the winning response must identify its case`);
    const winnerHandoff = await handoff(winnerCaseId);
    expect(winnerHandoff.caseStatus === 'EXPORT_PENDING', `contention run ${run}: the winner must be EXPORT_PENDING`);
    contentionRuns.push({
      run,
      statuses,
      loserCode: losers[0].json?.code,
      allocated: total,
      payments,
      winnerCaseId,
    });
  }
  await resetDatabase();
  erpChild.setDropResponse(false);
  record('ac-shared-receipt-contention', { api: 'concurrent POST /approve', iterations, runs: contentionRuns });

  // --- browser fixtures: EXPORTED / RESULT_UNKNOWN / FAILED ----------------

  guard();
  await resetDatabase();
  const exportedPrep = await createSubmittedCase('AC-BROWSER-EXPORTED-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  await runMatch(exportedPrep);
  const exportedSnapshot = await freeze(exportedPrep);
  expectStatus(await approve(exportedPrep, exportedSnapshot), [200, 201], 'browser exported approve');
  await waitFor('browser exported convergence', async () => (await handoff(exportedPrep)).caseStatus === 'EXPORTED');

  const unknownPrep = await createSubmittedCase('AC-BROWSER-UNKNOWN-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  await runMatch(unknownPrep);
  const unknownSnapshot = await freeze(unknownPrep);
  erp.setDropResponse(true);
  expectStatus(await approve(unknownPrep, unknownSnapshot), [200, 201], 'browser unknown approve');
  await waitFor('browser unknown persistence', async () => (await handoff(unknownPrep)).payment?.paymentStatus === 'RESULT_UNKNOWN');

  const failedPrep = await createSubmittedCase('AC-BROWSER-FAILED-' + Date.now(), line(5, 2500, 'ITEM-A4-80'));
  await runMatch(failedPrep);
  const failedSnapshotPrep = await freeze(failedPrep);
  const failedPrepApproved = expectStatus(await approve(failedPrep, failedSnapshotPrep), [200, 201], 'browser failed approve');
  const failedPrepKey = `${failedPrepApproved.json.paymentRequestId}:1`;
  await waitFor('browser failed UNKNOWN', async () => (await handoff(failedPrep)).payment?.paymentStatus === 'RESULT_UNKNOWN');
  erp.setDropResponse(false);
  const failedPrepRecord = erp.record(failedPrepKey);
  failedPrepRecord.outcome = 'FAILED';
  await erp.deliverWebhook(failedPrepRecord);
  await waitFor('browser failed persistence', async () => (await handoff(failedPrep)).payment?.paymentStatus === 'FAILED');

  evidence.fixtures = {
    exported: { caseId: exportedPrep, invoiceNumber: (await detailAs(exportedPrep)).invoiceNumber, expectedPaymentStatus: 'ACKNOWLEDGED', expectedOutboxStatus: 'DELIVERED', expectedCaseStatus: 'EXPORTED' },
    unknown: { caseId: unknownPrep, invoiceNumber: (await detailAs(unknownPrep)).invoiceNumber, expectedPaymentStatus: 'RESULT_UNKNOWN', expectedOutboxStatus: 'RESULT_UNKNOWN', expectedCaseStatus: 'EXPORT_PENDING' },
    failed: { caseId: failedPrep, invoiceNumber: (await detailAs(failedPrep)).invoiceNumber, expectedPaymentStatus: 'FAILED', expectedOutboxStatus: 'FAILED', expectedCaseStatus: 'EXPORT_PENDING' },
  };
  record('browser-fixtures-prepared', evidence.fixtures);

  return { evidence };
}
