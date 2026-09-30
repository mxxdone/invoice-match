// Read-API checks for the P1-10 verification script. Kept separate from the CLI
// so they can be driven with a stubbed request function.

import { VerifyError } from './verify-core.mjs';

function basic(user, password) {
  return 'Basic ' + Buffer.from(`${user}:${password}`, 'utf8').toString('base64');
}

function sanitized(prefix, response) {
  let code = '';
  try {
    const parsed = JSON.parse(response.body);
    if (parsed && typeof parsed.code === 'string') code = parsed.code;
  } catch {
    // non-JSON body
  }
  return new VerifyError(`${prefix} (status ${response.status}${code ? `, code ${code}` : ''})`);
}

export async function runChecks({ request, config, guard, log }) {
  const core = `http://127.0.0.1:${config.ports.core}`;
  const web = `http://127.0.0.1:${config.ports.web}`;
  const approver = { authorization: basic('approver', 'approver-pass') };
  const submitter = { authorization: basic('submitter', 'submitter-pass') };
  const pass = (name) => log(`PASS ${name}`);

  const anon = await request(`${core}/api/me`);
  if (anon.status !== 401) throw new VerifyError(`anonymous /api/me expected 401 (status ${anon.status})`);
  pass('anonymous /api/me is 401');

  const me = await request(`${core}/api/me`, { headers: approver });
  if (me.status !== 200 || !me.body.includes('APPROVER')) throw new VerifyError(`approver /api/me expected 200 with APPROVER (status ${me.status})`);
  pass('approver /api/me is 200');

  guard();
  const health = await request(`${core}/actuator/health`);
  if (health.status !== 200 || !health.body.includes('"status":"UP"')) throw new VerifyError(`isolated core health not UP (status ${health.status})`);
  pass('isolated core health is UP');

  guard();
  const create = await request(`${core}/api/invoice-cases`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-create', supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber: 'INV-VERIFY-9' }),
  });
  if (create.status !== 201) throw sanitized('create did not return 201', create);
  pass('create is 201');
  const created = JSON.parse(create.body);

  guard();
  const draft = await request(`${core}/api/invoice-cases/${created.id}/draft`, {
    method: 'PUT',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-draft', expectedCaseVersion: created.version, lines: [{ lineNumber: 1, rawItemName: 'Copy Paper', quantity: 5, unitPrice: 2500, confirmedItemId: 'ITEM-A4-80' }] }),
  });
  if (draft.status !== 200) throw sanitized('draft did not return 200', draft);
  pass('draft is 200');
  const drafted = JSON.parse(draft.body);

  guard();
  const submit = await request(`${core}/api/invoice-cases/${created.id}/submit`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-submit', expectedCaseVersion: drafted.version }),
  });
  if (submit.status !== 200) throw sanitized('submit did not return 200', submit);
  pass('submit is 200');

  const partial = await request(`${core}/api/invoice-cases?invoiceNumber=VERIFY&size=20`, { headers: approver });
  const partialBody = JSON.parse(partial.body);
  if (partial.status !== 200 || partialBody.totalItems !== 1) throw new VerifyError(`partial search expected totalItems 1 (status ${partial.status}, totalItems ${partialBody.totalItems})`);
  pass('partial invoice search finds the case');

  const kstDay = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  const range = await request(`${core}/api/invoice-cases?submittedFrom=${kstDay}T00:00:00.000000%2B09:00&submittedTo=${kstDay}T23:59:59.999999%2B09:00&size=100`, { headers: approver });
  const rangeBody = JSON.parse(range.body);
  if (range.status !== 200 || !(rangeBody.items ?? []).some((item) => item.invoiceNumber === 'INV-VERIFY-9')) throw new VerifyError(`KST inclusive day range did not find the case (status ${range.status})`);
  pass('KST inclusive day range finds the case');

  const proxyAnon = await request(`${web}/backend/api/me`);
  if (proxyAnon.status !== 401) throw new VerifyError(`proxy anonymous /api/me expected 401 (status ${proxyAnon.status})`);
  pass('proxy anonymous /api/me is 401');

  const proxyBlocked = await request(`${web}/backend/api/invoice-cases/${created.id}/approve`);
  if (proxyBlocked.status !== 404) throw new VerifyError(`proxy non-allowlisted path expected 404 (status ${proxyBlocked.status})`);
  pass('proxy refuses a non-allowlisted path');

  // --- live detail reads (P1-10 detail slice) ---

  guard();
  const approverDetail = await request(`${core}/api/invoice-cases/${created.id}`, { headers: approver });
  if (approverDetail.status !== 200 || !approverDetail.body.includes(created.id)) throw sanitized('approver case detail expected 200 with the id', approverDetail);
  pass('approver reads the case detail');

  const submitterDetail = await request(`${core}/api/invoice-cases/${created.id}`, { headers: submitter });
  if (submitterDetail.status !== 200) throw sanitized('submitter own case detail expected 200', submitterDetail);
  pass('submitter reads their own case detail');

  const otherSubmitter = await request(`${core}/api/invoice-cases/${created.id}`, { headers: { authorization: basic('submitter2', 'submitter2-pass') } });
  if (otherSubmitter.status !== 403) throw sanitized('another submitter reading the case expected 403', otherSubmitter);
  pass('another submitter is denied by ownership with 403');

  const unknown = await request(`${core}/api/invoice-cases/00000000-0000-4000-8000-000000000000`, { headers: approver });
  if (unknown.status !== 404) throw sanitized('unknown case detail expected 404', unknown);
  pass('unknown case detail is 404');

  const bundles = await request(`${core}/api/invoice-cases/${created.id}/evidence-bundles`, { headers: approver });
  const bundleList = JSON.parse(bundles.body);
  if (bundles.status !== 200 || !Array.isArray(bundleList) || bundleList.length < 1) throw new VerifyError(`evidence bundle list expected at least one version (status ${bundles.status})`);
  pass('evidence bundle list returns the submitted version');

  const bundleDetail = await request(`${core}/api/invoice-cases/${created.id}/evidence-bundles/${bundleList[0].version}`, { headers: approver });
  if (bundleDetail.status !== 200 || !bundleDetail.body.includes('lines')) throw sanitized('sealed evidence bundle expected 200 with lines', bundleDetail);
  pass('sealed evidence bundle payload is readable');

  const noMatch = await request(`${core}/api/invoice-cases/${created.id}/match`, { headers: approver });
  if (noMatch.status !== 404) throw sanitized('latest match before a run expected 404', noMatch);
  pass('latest match before any run is 404');

  const noSnapshot = await request(`${core}/api/invoice-cases/${created.id}/review-snapshots/latest`, { headers: approver });
  if (noSnapshot.status !== 404) throw sanitized('latest review snapshot before a freeze expected 404', noSnapshot);
  pass('latest review snapshot before any freeze is 404');

  const submitterAudit = await request(`${core}/api/invoice-cases/${created.id}/audit-entries`, { headers: submitter });
  if (submitterAudit.status !== 403) throw sanitized('submitter audit history expected 403', submitterAudit);
  pass('audit history is restricted to reviewer roles');

  const auditFirst = await request(`${core}/api/invoice-cases/${created.id}/audit-entries?limit=1`, { headers: approver });
  const firstPage = JSON.parse(auditFirst.body);
  if (auditFirst.status !== 200 || !Array.isArray(firstPage.entries) || firstPage.entries.length !== 1 || !firstPage.nextCursor) {
    throw new VerifyError(`audit first page expected one entry and a cursor (status ${auditFirst.status})`);
  }
  pass('audit first page returns one entry and a cursor');

  const auditNext = await request(`${core}/api/invoice-cases/${created.id}/audit-entries?limit=20&cursor=${encodeURIComponent(firstPage.nextCursor)}`, { headers: approver });
  const nextPage = JSON.parse(auditNext.body);
  if (auditNext.status !== 200 || !Array.isArray(nextPage.entries) || nextPage.entries.length < 1) throw sanitized('audit next page expected more entries', auditNext);
  if (nextPage.entries.some((entry) => entry.id === firstPage.entries[0].id)) throw new VerifyError('audit cursor repeated the first page entry');
  pass('audit cursor returns the next page without repeating');

  const handoff = await request(`${core}/api/invoice-cases/${created.id}/handoff`, { headers: approver });
  const handoffBody = handoff.status === 200 ? JSON.parse(handoff.body) : {};
  if (handoff.status !== 200 || handoffBody.payment != null) throw sanitized('handoff before approval expected 200 with a null payment', handoff);
  pass('handoff before approval has a null payment');

  const proxyDetail = await request(`${web}/backend/api/invoice-cases/${created.id}`, { headers: approver });
  if (proxyDetail.status !== 200 || !proxyDetail.body.includes(created.id)) throw sanitized('proxy allowlisted detail path expected 200', proxyDetail);
  pass('proxy forwards the allowlisted detail path');

  const proxyBadId = await request(`${web}/backend/api/invoice-cases/not-a-uuid/handoff`, { headers: approver });
  if (proxyBadId.status !== 404) throw sanitized('proxy non-UUID case path expected 404', proxyBadId);
  pass('proxy refuses a non-UUID case path');

  // --- seeded normal match and an older-result-after-resubmit fixture ---
  // All writes below go only to this run's throwaway database through the real
  // API; no approval/payment is created and the UI has no write path.

  const operator = { authorization: basic('operator', 'operator-pass') };
  const detailAs = async (headers) => {
    const response = await request(`${core}/api/invoice-cases/${created.id}`, { headers });
    if (response.status !== 200) throw sanitized('detail read failed during seed', response);
    return JSON.parse(response.body);
  };

  guard();
  const matchRun = await request(`${core}/api/invoice-cases/${created.id}/match`, {
    method: 'POST',
    headers: { ...operator, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-match-1' }),
  });
  if (matchRun.status !== 201) throw sanitized('operator match run expected 201', matchRun);
  const matchBody = JSON.parse(matchRun.body);
  if (matchBody.payload?.normal !== true || matchBody.payload?.evidenceBundle?.version !== 1) {
    throw new VerifyError('match run expected a normal result against evidence bundle v1');
  }
  pass('operator runs a normal match against evidence bundle v1');

  const latestMatch = await request(`${core}/api/invoice-cases/${created.id}/match`, { headers: approver });
  if (latestMatch.status !== 200 || JSON.parse(latestMatch.body).resultNumber !== matchBody.resultNumber) {
    throw sanitized('latest match expected the seeded result', latestMatch);
  }
  pass('latest match read returns the seeded result');

  guard();
  const freeze = await request(`${core}/api/invoice-cases/${created.id}/review-snapshots`, {
    method: 'POST',
    headers: { ...approver, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-snapshot-1', expectedCaseVersion: (await detailAs(approver)).version }),
  });
  if (freeze.status !== 200 && freeze.status !== 201) throw sanitized('freeze review snapshot expected 200/201', freeze);
  const snapshot = JSON.parse(freeze.body);
  pass('approver freezes a review snapshot');

  guard();
  const supplement = await request(`${core}/api/invoice-cases/${created.id}/supplement-requests`, {
    method: 'POST',
    headers: { ...approver, 'content-type': 'application/json' },
    body: JSON.stringify({
      requestId: 'verify-supplement-1',
      expectedCaseVersion: (await detailAs(approver)).version,
      reviewSnapshotId: snapshot.id,
      reviewPayloadHash: snapshot.payloadHash,
      reason: 'verify seed: resubmit for an older-result fixture',
    }),
  });
  if (supplement.status !== 200 && supplement.status !== 201) throw sanitized('supplement request expected 200/201', supplement);
  pass('approver requests a supplement');

  const afterSupplement = await detailAs(submitter);
  const revision = await request(`${core}/api/invoice-cases/${created.id}/revisions`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-revision-1', expectedCaseVersion: afterSupplement.version }),
  });
  if (revision.status !== 200 && revision.status !== 201) throw sanitized('open supplement revision expected 200/201', revision);
  pass('submitter opens the supplement revision');

  const opened = JSON.parse(revision.body);
  const replaced = await request(`${core}/api/invoice-cases/${created.id}/draft`, {
    method: 'PUT',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({
      requestId: 'verify-draft-2',
      expectedCaseVersion: opened.version,
      lines: [{ lineNumber: 1, rawItemName: 'Copy Paper', quantity: 6, unitPrice: 2500, confirmedItemId: 'ITEM-A4-80' }],
    }),
  });
  if (replaced.status !== 200) throw sanitized('draft replace for v2 expected 200', replaced);
  const resubmitted = await request(`${core}/api/invoice-cases/${created.id}/submit`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-submit-2', expectedCaseVersion: JSON.parse(replaced.body).version }),
  });
  if (resubmitted.status !== 200) throw sanitized('v2 resubmit expected 200', resubmitted);
  pass('submitter resubmits evidence bundle v2');

  const bundlesAfter = JSON.parse((await request(`${core}/api/invoice-cases/${created.id}/evidence-bundles`, { headers: approver })).body);
  const latestBundleVersion = Math.max(...bundlesAfter.map((bundle) => bundle.version));
  if (latestBundleVersion !== 2) throw new VerifyError(`expected evidence bundle v2 but latest was v${latestBundleVersion}`);
  const matchAfter = JSON.parse((await request(`${core}/api/invoice-cases/${created.id}/match`, { headers: approver })).body);
  if (matchAfter.payload?.evidenceBundle?.version !== 1) {
    throw new VerifyError(`latest match after resubmit should still cite bundle v1, was v${matchAfter.payload?.evidenceBundle?.version}`);
  }
  pass('latest match after resubmit is an older (v1) result while the current bundle is v2');

  const proxyMatch = await request(`${web}/backend/api/invoice-cases/${created.id}/match`, { headers: approver });
  if (proxyMatch.status !== 200) throw sanitized('proxy allowlisted match path expected 200', proxyMatch);
  const proxyBundles = await request(`${web}/backend/api/invoice-cases/${created.id}/evidence-bundles`, { headers: approver });
  if (proxyBundles.status !== 200) throw sanitized('proxy allowlisted evidence-bundles path expected 200', proxyBundles);
  pass('proxy forwards the allowlisted match and evidence-bundles paths');

  // --- write proxy (P1-10 authoring slice) ---

  guard();
  const proxyCreate = await request(`${web}/backend/api/invoice-cases`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-proxy-create', supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber: 'INV-VERIFY-PROXY' }),
  });
  if (proxyCreate.status !== 201) throw sanitized('proxy allowlisted create expected 201', proxyCreate);
  const proxyCreated = JSON.parse(proxyCreate.body);
  pass('proxy forwards an allowlisted create write');

  const proxyWrongMethod = await request(`${web}/backend/api/invoice-cases/${created.id}/draft`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-proxy-wrong-method', expectedCaseVersion: 1, lines: [] }),
  });
  if (proxyWrongMethod.status !== 404) throw sanitized('proxy draft with POST expected 404', proxyWrongMethod);
  pass('proxy refuses a write path with the wrong method');

  const proxyUnknownWrite = await request(`${web}/backend/api/me`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-proxy-unknown' }),
  });
  if (proxyUnknownWrite.status !== 404) throw sanitized('proxy non-allowlisted write expected 404', proxyUnknownWrite);
  pass('proxy refuses a non-allowlisted write path');

  const oversizedBody = JSON.stringify({ requestId: 'verify-proxy-large', supplierId: 'SUP-1', purchaseOrderId: 'PO-1001', invoiceNumber: 'X'.repeat(300000) });
  const proxyTooLarge = await request(`${web}/backend/api/invoice-cases`, {
    method: 'POST',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: oversizedBody,
  });
  if (proxyTooLarge.status !== 413) throw sanitized('proxy oversized request body expected 413', proxyTooLarge);
  pass('proxy bounds the request body to the Core API limit');

  guard();
  const proxyDraft = await request(`${web}/backend/api/invoice-cases/${proxyCreated.id}/draft`, {
    method: 'PUT',
    headers: { ...submitter, 'content-type': 'application/json' },
    body: JSON.stringify({ requestId: 'verify-proxy-draft', expectedCaseVersion: proxyCreated.version, lines: [{ lineNumber: 1, rawItemName: 'Proxy Paper', quantity: 3, unitPrice: 1000, confirmedItemId: null }] }),
  });
  if (proxyDraft.status !== 200) throw sanitized('proxy allowlisted draft expected 200', proxyDraft);
  pass('proxy forwards an allowlisted draft write');

  return {};
}
