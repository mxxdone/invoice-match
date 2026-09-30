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

  return {};
}
