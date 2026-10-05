// Real-browser acceptance flow for P4-06. Read as text by
// scripts/lib/p4-06-browser.mjs and executed through `playwright-cli run-code`
// with a live Playwright `page`. __WEB__, __OFFWEB__, __OUT__, __INVOICE__,
// __OFFINVOICE__ and __STEP__ are substituted.
//
// Credentials live only in the tab's React memory, so the flow moves through
// in-app links/buttons; a full page load would drop the session by design. The
// graph worker stop/restart happens outside the browser between steps, so the
// flow re-reads the authoritative state with the panel's own refresh action.
async (page) => {
  const WEB = '__WEB__';
  const OFFWEB = '__OFFWEB__';
  const OUT = '__OUT__';
  const INVOICE = '__INVOICE__';
  const OFFINVOICE = '__OFFINVOICE__';
  const STEP = '__STEP__';
  const steps = [];
  const httpErrors = [];
  const consoleErrors = [];
  const results = {};

  page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', (error) => consoleErrors.push('pageerror: ' + (error && error.message ? error.message : String(error))));
  page.on('response', (response) => {
    if (response.status() >= 400) {
      const url = response.url();
      httpErrors.push(`${response.status()} ${response.request().method()} ${url.split('/backend')[1] ?? url}`);
    }
  });

  const must = (condition, message) => { if (!condition) throw new Error('ASSERT: ' + message); };
  const shot = async (name) => { await page.screenshot({ path: `${OUT}/${name}.png` }); steps.push('shot:' + name); };
  const onPath = (path) => new URL(page.url()).pathname === path;
  const caseIdFromUrl = () => { const m = page.url().match(/\/cases\/([0-9a-fA-F-]{36})/); must(m, 'case id in ' + page.url()); return m[1]; };

  async function login(base, user, pass) {
    await page.goto(base + '/login');
    await page.getByPlaceholder('사용자명을 입력하세요').fill(user);
    await page.getByPlaceholder('비밀번호를 입력하세요').fill(pass);
    await page.locator('button.login-submit').click();
    await page.waitForURL('**/cases', { timeout: 20000 });
  }
  async function switchUser(base, user, pass) {
    if (await page.getByRole('button', { name: '로그아웃' }).count()) {
      await page.getByRole('button', { name: '로그아웃' }).first().click();
      await page.waitForURL('**/login', { timeout: 20000 });
    }
    await login(base, user, pass);
  }
  async function goList() {
    await page.getByRole('link', { name: '청구서' }).first().click();
    await page.waitForURL('**/cases', { timeout: 20000 });
  }
  async function openDetail(invoiceNumber) {
    if (!onPath('/cases')) await goList();
    await page.getByLabel('청구서 검색').fill(invoiceNumber);
    await page.getByRole('button', { name: '검색' }).click();
    await page.getByRole('link', { name: invoiceNumber }).first().click();
    await page.waitForURL(/\/cases\/[0-9a-fA-F-]{36}/, { timeout: 25000 });
    await page.locator('h1:has-text("청구서 상세")').waitFor({ timeout: 20000 });
    return caseIdFromUrl();
  }
  const graphPanel = () => page.locator('section[aria-label="AI 확인·재개"]').first();
  async function refreshGraph() {
    await graphPanel().getByRole('button', { name: 'AI 분석 상태 새로 조회' }).click();
  }

  try {
    await page.setViewportSize({ width: 1440, height: 980 });

    if (STEP === 'reserve-confirm') {
      await switchUser(WEB, 'operator', 'operator-pass');
      await openDetail(INVOICE);
      await graphPanel().waitFor({ timeout: 20000 });
      const reserve = graphPanel().getByRole('button', { name: 'AI 분석 예약' });
      await reserve.waitFor({ timeout: 20000 });
      await reserve.click();
      const save = graphPanel().getByRole('button', { name: '사람 확인 저장' });
      const refresh = graphPanel().getByRole('button', { name: 'AI 분석 상태 새로 조회' });
      const deadline = Date.now() + 150000;
      while (Date.now() < deadline && (await save.count()) === 0) {
        const alert = page.locator('.review-warning[role="alert"]').first();
        if (await alert.count()) {
          const notice = await alert.innerText().catch(() => '');
          if (notice.trim()) throw new Error('graph reserve failed: ' + notice.replace(/\s+/g, ' ').trim());
        }
        if (await refresh.count()) await refresh.click();
        await page.waitForTimeout(2500);
      }
      must(await save.count() > 0, 'operator pending confirm form reached WAITING_HUMAN');
      await graphPanel().getByText('문서 후보 확인 필요').first().waitFor({ timeout: 20000 });
      must(await graphPanel().getByText('대기 사유').count() > 0, 'pending reason section is visible');
      await graphPanel().getByRole('radio', { name: '원문 후보를 확인함' }).check();
      await page.getByLabel('확인 사유').fill('원문 후보와 추출 결과를 대조해 확인했습니다.');
      await save.click();
      await page.getByText(/사람 확인이 저장되어 재개가 예약되었습니다/).first().waitFor({ timeout: 30000 });
      results.pendingReserveConfirm = true;
      await shot('p4-06-01-pending-confirmed');
    } else if (STEP === 'verify-completed') {
      await refreshGraph();
      await graphPanel().getByText('분석 완료').first().waitFor({ timeout: 60000 });
      results.resumeCompleted = true;
      await shot('p4-06-02-resume-completed');
    } else if (STEP === 'mapping') {
      await switchUser(WEB, 'approver', 'approver-pass');
      await openDetail(INVOICE);
      await page.locator('button:text-is("검토 대상 동결")').first().click();
      const mapper = page.locator('.mapper-grid');
      await mapper.locator('select').first().waitFor({ timeout: 30000 });
      await mapper.locator('select').first().selectOption({ index: 1 });
      await mapper.locator('input').first().fill('ITEM-A4-80');
      await page.locator('button:text-is("매핑 확정")').first().click();
      await page.getByText(/작업이 서버에 반영되었습니다/).first().waitFor({ timeout: 30000 });
      await refreshGraph();
      await graphPanel().getByText(/이전 입력의 분석/).first().waitFor({ timeout: 30000 });
      results.mappingMadeStale = true;
      await shot('p4-06-03-mapping-stale');
    } else if (STEP === 'successor') {
      await switchUser(WEB, 'operator', 'operator-pass');
      await openDetail(INVOICE);
      await refreshGraph();
      const successor = graphPanel().getByRole('button', { name: '이 입력으로 새 분석 예약' });
      await successor.waitFor({ timeout: 30000 });
      await successor.click();
      await page.getByText(/작업이 서버에 반영되었습니다/).first().waitFor({ timeout: 30000 });
      await refreshGraph();
      await graphPanel().getByText('분석 예약됨').first().waitFor({ timeout: 30000 });
      results.successorReserved = true;
      await shot('p4-06-04-successor-reserved');
    } else if (STEP === 'submitter-read') {
      await switchUser(WEB, 'submitter', 'submitter-pass');
      await openDetail(INVOICE);
      must(await graphPanel().count() === 0, 'graph panel is hidden for a submitter');
      must(await page.locator('section[aria-label="AI 검토 자료"]').count() === 0, 'v1 panel is hidden for a submitter');
      results.submitterRead = true;
      await shot('p4-06-05-submitter-read');
    } else if (STEP === 'ai-off') {
      await switchUser(OFFWEB, 'approver', 'approver-pass');
      await openDetail(OFFINVOICE);
      await page.locator('button:text-is("검토 대상 동결")').first().click();
      const approve = page.locator('button:text-is("승인")').first();
      await approve.waitFor({ state: 'visible', timeout: 30000 });
      await page.waitForFunction(() => {
        const b = Array.from(document.querySelectorAll('button')).find((x) => x.textContent.trim() === '승인');
        return b && !b.disabled;
      }, { timeout: 30000 });
      await approve.click();
      await page.waitForFunction(() => {
        const node = document.querySelector('.case-status');
        return node && /인계/.test(node.textContent || '');
      }, { timeout: 30000 });
      results.aiOffApproved = true;
      await shot('p4-06-06-ai-off-approved');
    } else if (STEP === 'reload') {
      await page.reload();
      await page.waitForURL(/\/cases\/[0-9a-fA-F-]{36}/, { timeout: 20000 }).catch(() => {});
    } else {
      throw new Error('unknown step ' + STEP);
    }

    const unexpectedHttpErrors = httpErrors.filter((entry) => !/^404 /.test(entry));
    const unexpectedConsoleErrors = consoleErrors.filter((m) => !/Failed to load resource: the server responded with a status of (404|403)/.test(m));
    return { ok: true, step: STEP, results, httpErrors, consoleErrors, unexpectedHttpErrors, unexpectedConsoleErrors, steps };
  } catch (error) {
    const where = steps.length > 0 ? steps[steps.length - 1] : 'start';
    throw new Error('[' + STEP + '/' + where + '] url=' + page.url() + ' :: ' + (error && error.message ? error.message : String(error)));
  }
}
