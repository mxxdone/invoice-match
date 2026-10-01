// Browser acceptance flow for P1-10. This file is read as text by
// scripts/browser/p1-10-browser.mjs and executed through `playwright-cli
// run-code`, which invokes it with a live Playwright `page`. __WEB__ and __OUT__
// are substituted with the isolated web origin and the artifact directory.
//
// Credentials live only in the tab's React memory, so after signing in the flow
// navigates through in-app links/buttons; a full page load would drop the
// session by design.
async (page) => {
  const WEB = '__WEB__';
  const OUT = '__OUT__';
  const stamp = Date.now().toString(36).toUpperCase();
  const steps = [];
  const consoleErrors = [];
  const httpErrors = [];
  const mutations = [];
  const writeRequestIds = [];
  const results = {};

  page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', (error) => consoleErrors.push('pageerror: ' + (error && error.message ? error.message : String(error))));
  page.on('request', (request) => {
    const url = request.url();
    if (url.includes('/backend/') && request.method() !== 'GET') {
      try {
        const body = JSON.parse(request.postData() ?? '{}');
        if (typeof body.requestId === 'string') writeRequestIds.push({ url: url.split('/backend')[1], requestId: body.requestId });
      } catch { /* non-JSON body */ }
    }
  });
  page.on('response', (response) => {
    const url = response.url();
    if (url.includes('/backend/') && response.request().method() !== 'GET') {
      mutations.push(`${response.request().method()} ${url.split('/backend')[1]} -> ${response.status()}`);
    }
    if (response.status() >= 400) {
      httpErrors.push(`${response.status()} ${response.request().method()} ${url.split('/backend')[1] ?? url}`);
    }
  });

  const must = (condition, message) => { if (!condition) throw new Error('ASSERT: ' + message); };
  const shot = async (name) => { await page.screenshot({ path: `${OUT}/${name}.png` }); steps.push('shot:' + name); };
  const caseIdFromUrl = () => {
    const match = page.url().match(/\/cases\/([0-9a-fA-F-]{36})/);
    must(match, 'expected a case id in ' + page.url());
    return match[1];
  };
  const onPath = (path) => new URL(page.url()).pathname === path;

  async function login(user, pass) {
    await page.goto(WEB + '/login');
    await page.getByPlaceholder('사용자명을 입력하세요').fill(user);
    await page.getByPlaceholder('비밀번호를 입력하세요').fill(pass);
    await page.locator('button.login-submit').click();
    await page.waitForURL('**/cases', { timeout: 20000 });
  }
  async function switchUser(user, pass) {
    if (await page.getByRole('button', { name: '로그아웃' }).count()) {
      await page.getByRole('button', { name: '로그아웃' }).first().click();
      await page.waitForURL('**/login', { timeout: 20000 });
    }
    await login(user, pass);
  }
  async function goList() {
    await page.getByRole('link', { name: '청구서' }).first().click();
    await page.waitForURL('**/cases', { timeout: 20000 });
  }
  async function openDetailByInvoice(invoiceNumber) {
    if (!onPath('/cases')) await goList();
    await page.getByLabel('청구서 검색').fill(invoiceNumber);
    await page.getByRole('button', { name: '검색' }).click();
    await page.getByRole('link', { name: invoiceNumber }).first().click();
    await page.waitForURL(/\/cases\/[0-9a-fA-F-]{36}/, { timeout: 25000 });
    await page.locator('h1:has-text("청구서 상세")').waitFor({ timeout: 20000 });
    return caseIdFromUrl();
  }
  async function createAndSubmit(invoiceNumber, item) {
    if (!onPath('/cases')) await goList();
    await page.getByRole('link', { name: '청구 작성' }).first().click();
    await page.waitForURL('**/cases/new', { timeout: 20000 });
    await page.locator('.field-grid input').nth(0).fill('SUP-1');
    await page.locator('.field-grid input').nth(1).fill('PO-1001');
    await page.locator('.field-grid input').nth(2).fill(invoiceNumber);
    await page.getByLabel('1번 품목명').fill('Copy Paper');
    await page.getByLabel('1번 수량').fill('5');
    await page.getByLabel('1번 단가').fill('2500');
    if (item) await page.getByLabel('1번 품목 ID').fill(item);
    await page.locator('button.primary:has-text("제출")').click();
    await page.waitForURL(/\/cases\/[0-9a-fA-F-]{36}/, { timeout: 30000 });
    await page.locator('h1:has-text("청구서 상세")').waitFor({ timeout: 20000 });
    return caseIdFromUrl();
  }
  async function runMatch(invoiceNumber) {
    await openDetailByInvoice(invoiceNumber);
    await page.locator('button:has-text("대사 실행")').first().click();
    await page.getByText(/비교 결과 #/).first().waitFor({ timeout: 30000 });
  }
  async function freezeAndWaitApprove(invoiceNumber) {
    await openDetailByInvoice(invoiceNumber);
    await page.locator('button:text-is("검토 대상 동결")').first().click();
    const approve = page.locator('button:text-is("승인")').first();
    await approve.waitFor({ state: 'visible', timeout: 30000 });
    await page.waitForFunction(() => {
      const button = Array.from(document.querySelectorAll('button')).find((item) => item.textContent.trim() === '승인');
      return button && !button.disabled;
    }, { timeout: 30000 });
    return approve;
  }

  try {
  // --- normal: create → submit → match → freeze → approve → handoff
  steps.push('normal:start');
  await page.setViewportSize({ width: 1440, height: 900 });
  await switchUser('submitter', 'submitter-pass');
  const normalInvoice = 'INV-UI-NORMAL-' + stamp;
  steps.push('normal:create');
  await createAndSubmit(normalInvoice, 'ITEM-A4-80');
  await page.getByText(normalInvoice).first().waitFor({ timeout: 20000 });
  await shot('normal-detail-submitted');

  steps.push('normal:operator-match');
  await switchUser('operator', 'operator-pass');
  await runMatch(normalInvoice);

  steps.push('normal:approver');
  await switchUser('approver', 'approver-pass');
  const approveButton = await freezeAndWaitApprove(normalInvoice);
  await approveButton.click();
  await page.locator('.case-status:has-text("인계 대기")').first().waitFor({ timeout: 30000 });
  results.normalApproved = true;
  await shot('normal-approved');

  await switchUser('operator', 'operator-pass');
  await page.getByRole('link', { name: '운영 작업' }).first().click();
  await page.waitForURL('**/operations', { timeout: 20000 });
  await page.locator('button[role="tab"]:has-text("인계 대기")').click();
  const exportedRow = page.locator('tr', { hasText: normalInvoice }).first();
  await exportedRow.waitFor({ timeout: 25000 });
  await exportedRow.locator('button.row-detail').click();
  await page.getByRole('link', { name: 'ERP 인계 상세' }).click();
  await page.waitForURL('**/handoff?case=*', { timeout: 20000 });
  await page.getByText('지급요청과 전송 상태').first().waitFor({ timeout: 20000 });
  await page.getByText(/실제 지급·송금은 외부 ERP/).first().waitFor({ timeout: 20000 });
  await page.getByText('고정된 요청 식별정보').first().waitFor({ timeout: 20000 });
  // The relay is not enabled in this stack, so the real state is NOT_SENT; this
  // records the payment/transfer disclaimer shown for that actual state.
  await page.getByText('전송 전').first().waitFor({ timeout: 20000 });
  results.handoffPaymentAndTransferDisclaimer = true;
  await shot('handoff-approved');

  await goList();
  await page.getByLabel('청구서 검색').fill(normalInvoice);
  await page.getByRole('button', { name: '검색' }).click();
  await page.getByText(normalInvoice).first().waitFor({ timeout: 20000 });
  results.listBack = true;
  await shot('list-back');

  // --- exception mapping: unconfirmed line → human mapping → successor snapshot
  steps.push('mapping:start');
  await switchUser('submitter', 'submitter-pass');
  const mapInvoice = 'INV-UI-MAP-' + stamp;
  await createAndSubmit(mapInvoice, null);
  await switchUser('operator', 'operator-pass');
  await runMatch(mapInvoice);
  await page.getByText(/품목 매핑 미확정/).first().waitFor({ timeout: 20000 });
  await switchUser('approver', 'approver-pass');
  await openDetailByInvoice(mapInvoice);
  await page.locator('button:text-is("검토 대상 동결")').first().click();
  const mappingSelect = page.locator('select[aria-label="매핑할 라인"]');
  await mappingSelect.waitFor({ state: 'visible', timeout: 30000 });
  await mappingSelect.selectOption('1');
  await page.locator('label:has-text("확정 품목 ID") input').fill('ITEM-A4-80');
  await page.locator('button:text-is("매핑 확정")').first().click();
  await page.locator('button[role="tab"]:has-text("검토 결정")').click();
  await page.getByText('품목 매핑').first().waitFor({ timeout: 30000 });
  results.mapping = true;
  await shot('mapping-successor');

  // --- supplement → resubmit v2 → stale (mismatch) view
  steps.push('supplement:start');
  await switchUser('submitter', 'submitter-pass');
  const supInvoice = 'INV-UI-SUP-' + stamp;
  await createAndSubmit(supInvoice, 'ITEM-A4-80');
  await switchUser('operator', 'operator-pass');
  await runMatch(supInvoice);
  await switchUser('approver', 'approver-pass');
  await openDetailByInvoice(supInvoice);
  await page.locator('button:text-is("검토 대상 동결")').first().click();
  const supplementButton = page.locator('button:text-is("보완 요청")').first();
  await supplementButton.waitFor({ state: 'visible', timeout: 30000 });
  await supplementButton.click();
  await page.locator('.reason-label textarea').fill('검증: 수량 근거 보완 요청');
  await page.locator('button:text-is("보완 요청 기록")').first().click();
  await page.locator('.case-status:has-text("보완 대기")').first().waitFor({ timeout: 30000 });

  await switchUser('submitter', 'submitter-pass');
  await openDetailByInvoice(supInvoice);
  await page.getByRole('link', { name: '보완 작성' }).click();
  await page.waitForURL(/\/cases\/new\?supplement=/, { timeout: 20000 });
  await page.getByLabel('1번 수량').fill('4');
  await page.locator('button.primary:has-text("보완 재제출")').click();
  await page.waitForURL(new RegExp('/cases/[0-9a-fA-F-]{36}'), { timeout: 30000 });
  await page.waitForFunction(() => document.body.innerText.includes('증빙 버전') && document.body.innerText.includes('v2'), { timeout: 30000 });
  results.supplementResubmittedV2 = true;
  await shot('supplement-v2');

  await switchUser('approver', 'approver-pass');
  await openDetailByInvoice(supInvoice);
  await page.getByText(/현재 자료와 일치 여부: 불일치/).first().waitFor({ timeout: 30000 });
  const staleApprove = page.locator('button:text-is("승인")');
  must((await staleApprove.count()) === 0 || (await staleApprove.first().isDisabled()), 'stale subject must not offer an enabled approve');
  results.staleBlocked = true;
  await shot('stale-mismatch');

  // --- reject
  steps.push('reject:start');
  await switchUser('submitter', 'submitter-pass');
  const rejectInvoice = 'INV-UI-REJ-' + stamp;
  await createAndSubmit(rejectInvoice, 'ITEM-A4-80');
  await switchUser('operator', 'operator-pass');
  await runMatch(rejectInvoice);
  await switchUser('approver', 'approver-pass');
  await openDetailByInvoice(rejectInvoice);
  await page.locator('button:text-is("검토 대상 동결")').first().click();
  const rejectButton = page.locator('button:text-is("청구 거절")').first();
  await rejectButton.waitFor({ state: 'visible', timeout: 30000 });
  await rejectButton.click();
  await page.locator('.reason-label textarea').fill('검증: 처리 종료 사유');
  await page.locator('button:text-is("거절 기록")').first().click();
  await page.locator('.case-status:has-text("청구 거절")').first().waitFor({ timeout: 30000 });
  results.reject = true;
  await shot('reject');

  // --- self-approval denial (dual-role identity; real server 403)
  steps.push('self:start');
  await switchUser('dual', 'approver-pass');
  const selfInvoice = 'INV-UI-SELF-' + stamp;
  await createAndSubmit(selfInvoice, 'ITEM-A4-80');
  await page.locator('button:has-text("대사 실행")').first().click();
  await page.getByText(/비교 결과 #/).first().waitFor({ timeout: 30000 });
  await page.locator('button:text-is("검토 대상 동결")').first().click();
  const selfApprove = page.locator('button:text-is("승인")').first();
  await selfApprove.waitFor({ state: 'visible', timeout: 30000 });
  await page.waitForFunction(() => {
    const button = Array.from(document.querySelectorAll('button')).find((item) => item.textContent.trim() === '승인');
    return button && !button.disabled;
  }, { timeout: 30000 });
  await selfApprove.click();
  const alert = page.locator('.review-warning[role="alert"]').first();
  await alert.waitFor({ timeout: 30000 });
  const alertText = (await alert.innerText()).trim();
  must(/403|FORBIDDEN|승인|approve|거부/i.test(alertText), 'self-approval denial should be surfaced, got: ' + alertText);
  results.selfApprovalDenied = true;
  await shot('self-approval-denied');

  // --- unknown-result recovery: an ambiguous operator match retries the exact id
  steps.push('unknown:start');
  await switchUser('submitter', 'submitter-pass');
  const unknownInvoice = 'INV-UI-UNK-' + stamp;
  await createAndSubmit(unknownInvoice, 'ITEM-A4-80');
  await switchUser('operator', 'operator-pass');
  await openDetailByInvoice(unknownInvoice);
  let abortedOnce = false;
  await page.route('**/backend/api/invoice-cases/**/match', async (route) => {
    if (!abortedOnce) {
      abortedOnce = true;
      await route.abort('failed');
    } else {
      await route.continue();
    }
  });
  await page.locator('button:has-text("대사 실행")').first().click();
  await page.getByText(/이전 요청의 결과가 확정되지 않았습니다/).first().waitFor({ timeout: 30000 });
  const retryMatch = page.locator('button:text-is("같은 요청 다시 시도")').first();
  await retryMatch.waitFor({ state: 'visible', timeout: 20000 });
  must(!(await retryMatch.isDisabled()), 'the unresolved retry button must be enabled');
  const matchIdsBefore = writeRequestIds.filter((entry) => /\/match$/.test(entry.url)).map((entry) => entry.requestId);
  await retryMatch.click();
  await page.getByText(/비교 결과 #/).first().waitFor({ timeout: 30000 });
  const matchIdsAfter = writeRequestIds.filter((entry) => /\/match$/.test(entry.url)).map((entry) => entry.requestId);
  must(matchIdsAfter.length >= matchIdsBefore.length + 1, 'the retried match should have been sent');
  must(matchIdsAfter[matchIdsAfter.length - 1] === matchIdsAfter[matchIdsAfter.length - 2], 'the retried match must reuse the exact request id');
  await page.unroute('**/backend/api/invoice-cases/**/match');
  results.unknownRetry = true;
  await shot('unknown-retry');

  // --- narrow viewport has no document-level horizontal overflow
  await page.setViewportSize({ width: 1024, height: 768 });
  await goList();
  await page.getByRole('link', { name: '청구 작성' }).first().click();
  await page.waitForURL('**/cases/new', { timeout: 20000 });
  await page.getByLabel('1번 품목명').waitFor({ timeout: 20000 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  must(overflow <= 2, 'document horizontal overflow at 1024px: ' + overflow);
  results.viewportNoOverflow = true;
  await shot('viewport-1024');

  // The absent-section reads are a 404 by contract and the exercised
  // self-approval is a 403; anything else at 4xx/5xx is a real failure.
  const allowedHttpError = (entry) => {
    const parts = entry.split(' ');
    const status = parts[0];
    const path = parts[parts.length - 1];
    if (status === '404') return /\/match$/.test(path) || /\/review-snapshots\/latest$/.test(path);
    if (status === '403') return /\/approve$/.test(path);
    return false;
  };
  const unexpectedHttpErrors = httpErrors.filter((entry) => !allowedHttpError(entry));
  const unexpectedConsoleErrors = consoleErrors.filter(
    (message) => !/Failed to load resource: the server responded with a status of (404|403)/.test(message)
      && !/Failed to load resource: net::ERR_/.test(message),
  );
  return { ok: true, results, mutations, writeRequestIds, consoleErrors, unexpectedConsoleErrors, httpErrors, unexpectedHttpErrors, steps };
  } catch (error) {
    const where = steps.length > 0 ? steps[steps.length - 1] : 'unknown';
    let alertText = '';
    try { alertText = (await page.locator('.review-warning[role="alert"]').first().innerText()).trim(); } catch { /* none */ }
    throw new Error('[' + where + '] url=' + page.url() + (alertText ? ' alert=' + alertText : '') + ' :: ' + (error && error.message ? error.message : String(error)));
  }
}
