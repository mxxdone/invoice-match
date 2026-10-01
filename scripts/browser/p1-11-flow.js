// Browser acceptance flow for P1-11. Read as text by
// scripts/browser/p1-11-browser.mjs and executed through `playwright-cli
// run-code`, which invokes it with a live Playwright `page`. __WEB__, __OUT__
// and the three prepared fixture invoice numbers are substituted.
//
// Credentials live only in the tab's React memory, so after signing in the flow
// navigates through in-app links/buttons; a full page load would drop the
// session by design.
async (page) => {
  const WEB = '__WEB__';
  const OUT = '__OUT__';
  const FIX = {
    exported: '__FIX_EXPORTED_INVOICE__',
    unknown: '__FIX_UNKNOWN_INVOICE__',
    failed: '__FIX_FAILED_INVOICE__',
  };
  const stamp = Date.now().toString(36).toUpperCase();
  const steps = [];
  const consoleErrors = [];
  const httpErrors = [];
  const mutations = [];
  const results = {};

  page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', (error) => consoleErrors.push('pageerror: ' + (error && error.message ? error.message : String(error))));
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
  async function goOperations(tabLabel) {
    await page.getByRole('link', { name: '운영 작업' }).first().click();
    await page.waitForURL('**/operations', { timeout: 20000 });
    await page.locator(`button[role="tab"]:has-text("${tabLabel}")`).click();
    await page.locator('table tbody tr').first().waitFor({ timeout: 25000 });
  }
  async function openHandoff(invoiceNumber, tabLabel) {
    await goOperations(tabLabel);
    const row = page.locator('tr', { hasText: invoiceNumber }).first();
    await row.waitFor({ timeout: 25000 });
    await row.locator('button.row-detail').click();
    await page.getByRole('link', { name: 'ERP 인계 상세' }).click();
    await page.waitForURL('**/handoff?case=*', { timeout: 20000 });
    await page.getByText('지급요청과 전송 상태').first().waitFor({ timeout: 20000 });
  }

  try {
    // --- live write path: create → submit → match → freeze → approve ---------
    steps.push('live:start');
    await page.setViewportSize({ width: 1440, height: 900 });
    await switchUser('submitter', 'submitter-pass');
    const liveInvoice = 'INV-P111-LIVE-' + stamp;
    await createAndSubmit(liveInvoice, 'ITEM-A4-80');
    await switchUser('operator', 'operator-pass');
    await runMatch(liveInvoice);
    await switchUser('approver', 'approver-pass');
    const approveButton = await freezeAndWaitApprove(liveInvoice);
    await approveButton.click();
    await page.waitForFunction(() => {
      const node = document.querySelector('.case-status');
      return node && /인계/.test(node.textContent || '');
    }, { timeout: 30000 });
    results.liveApproval = true;
    await shot('live-approved');

    // --- handoff state: ACKNOWLEDGED / DELIVERED / EXPORTED ------------------
    steps.push('handoff:exported');
    await switchUser('operator', 'operator-pass');
    await openHandoff(FIX.exported, '인계 완료');
    await page.getByText('인계 완료').first().waitFor({ timeout: 20000 });
    await page.getByText('전달됨').first().waitFor({ timeout: 20000 });
    await page.getByText(/송금 완료가 아니라/).first().waitFor({ timeout: 20000 });
    await page.getByText(/이 서비스는 지급요청을 안전하게 인계/).first().waitFor({ timeout: 20000 });
    results.handoffExported = true;
    await shot('handoff-exported');

    // --- handoff state: RESULT_UNKNOWN --------------------------------------
    steps.push('handoff:unknown');
    await openHandoff(FIX.unknown, '인계 대기');
    await page.getByText('결과 불명').first().waitFor({ timeout: 20000 });
    await page.getByText(/자동 재전송하지 않습니다/).first().waitFor({ timeout: 20000 });
    results.handoffResultUnknown = true;
    await shot('handoff-result-unknown');

    // --- handoff state: FAILED ----------------------------------------------
    steps.push('handoff:failed');
    await openHandoff(FIX.failed, '인계 대기');
    await page.getByText('인계 실패').first().waitFor({ timeout: 20000 });
    await page.locator('.handoff-grid').getByText('실패').first().waitFor({ timeout: 20000 });
    results.handoffFailed = true;
    await shot('handoff-failed');

    // --- list return and narrow viewport ------------------------------------
    await goList();
    await page.getByLabel('청구서 검색').fill(liveInvoice);
    await page.getByRole('button', { name: '검색' }).click();
    await page.getByText(liveInvoice).first().waitFor({ timeout: 20000 });
    results.listBack = true;

    await page.setViewportSize({ width: 1024, height: 768 });
    await page.getByRole('link', { name: '청구 작성' }).first().click();
    await page.waitForURL('**/cases/new', { timeout: 20000 });
    await page.getByLabel('1번 품목명').waitFor({ timeout: 20000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    must(overflow <= 2, 'document horizontal overflow at 1024px: ' + overflow);
    results.viewportNoOverflow = true;
    await shot('viewport-1024');

    // A missing match result or review snapshot is an empty section by contract
    // and is a 404; anything else at 4xx/5xx is a real failure.
    const allowedHttpError = (entry) => {
      const parts = entry.split(' ');
      const status = parts[0];
      const path = parts[parts.length - 1];
      if (status === '404') return /\/match$/.test(path) || /\/review-snapshots\/latest$/.test(path);
      return false;
    };
    const unexpectedHttpErrors = httpErrors.filter((entry) => !allowedHttpError(entry));
    const unexpectedConsoleErrors = consoleErrors.filter(
      (message) => !/Failed to load resource: the server responded with a status of (404|403)/.test(message)
        && !/Failed to load resource: net::ERR_/.test(message),
    );
    return { ok: true, results, mutations, consoleErrors, unexpectedConsoleErrors, httpErrors, unexpectedHttpErrors, steps };
  } catch (error) {
    const where = steps.length > 0 ? steps[steps.length - 1] : 'unknown';
    throw new Error('[' + where + '] url=' + page.url() + ' :: ' + (error && error.message ? error.message : String(error)));
  }
}
