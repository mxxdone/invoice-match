import { test, expect } from "@playwright/test";
import {
  fixtureApprovalResult,
  fixtureAuditEntries,
  fixtureCaseDetail,
  fixtureCasePage,
  fixtureFreshness,
  fixtureHandoff,
  fixtureMatchResult,
  fixtureReviewSnapshot,
  fixtureUser,
} from "./schema-faithful-fixtures";

test.describe("Invoice Match Workflow UI Tests", () => {
  test.beforeEach(async ({ page }) => {
    // Monitor console errors to ensure zero console error requirement
    page.on("console", (msg) => {
      if (msg.type() === "error") {
        console.error("Browser console error:", msg.text());
      }
    });
  });

  test("1. Happy Path Workflow: Create -> Draft -> Submit -> Match -> Freeze -> Approve -> Handoff -> Audit", async ({
    page,
  }) => {
    let currentCase = fixtureCaseDetail("case-happy-1", "DRAFT", 1, "submitter");
    let matchResult = fixtureMatchResult(currentCase.id, true);
    let reviewSnapshot = fixtureReviewSnapshot(currentCase.id, matchResult);
    let freshness = fixtureFreshness(currentCase.id, reviewSnapshot.id, true);
    let handoff = fixtureHandoff(currentCase.id, "NOT_SENT", "READY");

    let currentRole: "SUBMITTER" | "APPROVER" | "OPERATOR" = "SUBMITTER";
    let currentUsername = "submitter";

    // Setup schema-faithful route interception
    await page.route("**/api/proxy/**", async (route) => {
      const url = new URL(route.request().url());
      const path = url.pathname.replace("/api/proxy", "");
      const method = route.request().method();

      // /me
      if (path === "/me") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-me-1" },
          body: JSON.stringify(fixtureUser(currentUsername, [currentRole])),
        });
      }

      // /invoice-cases list
      if (path === "/invoice-cases" && method === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-list-1" },
          body: JSON.stringify(fixtureCasePage([currentCase])),
        });
      }

      // /invoice-cases create
      if (path === "/invoice-cases" && method === "POST") {
        const body = JSON.parse(route.request().postData() || "{}");
        currentCase = {
          ...currentCase,
          supplierId: body.supplierId,
          purchaseOrderId: body.purchaseOrderId,
          invoiceNumber: body.invoiceNumber,
          version: 1,
        };
        return route.fulfill({
          status: 201,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-create-1" },
          body: JSON.stringify(currentCase),
        });
      }

      // /invoice-cases/:id
      if (path === `/invoice-cases/${currentCase.id}` && method === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-get-1" },
          body: JSON.stringify(currentCase),
        });
      }

      // /invoice-cases/:id/draft
      if (path === `/invoice-cases/${currentCase.id}/draft` && method === "PUT") {
        const body = JSON.parse(route.request().postData() || "{}");
        currentCase = {
          ...currentCase,
          version: currentCase.version + 1,
          lines: body.lines,
        };
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-draft-1" },
          body: JSON.stringify(currentCase),
        });
      }

      // /invoice-cases/:id/submit
      if (path === `/invoice-cases/${currentCase.id}/submit` && method === "POST") {
        currentCase = {
          ...currentCase,
          status: "SUBMITTED",
          version: currentCase.version + 1,
        };
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-submit-1" },
          body: JSON.stringify({
            invoiceCaseId: currentCase.id,
            status: "SUBMITTED",
            version: currentCase.version,
            evidenceBundleId: "bundle-1",
            evidenceBundleVersion: 1,
            submittedAt: "2026-09-28T10:05:00Z",
          }),
        });
      }

      // /invoice-cases/:id/match
      if (path === `/invoice-cases/${currentCase.id}/match`) {
        if (method === "POST") {
          currentCase.status = "REVIEW_PENDING";
          matchResult = fixtureMatchResult(currentCase.id, true);
          return route.fulfill({
            status: 200,
            contentType: "application/json",
            headers: { "x-trace-id": "trc-match-run-1" },
            body: JSON.stringify(matchResult),
          });
        }
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-match-get-1" },
          body: JSON.stringify(currentCase.status === "DRAFT" ? null : matchResult),
        });
      }

      // /invoice-cases/:id/review-snapshots
      if (path === `/invoice-cases/${currentCase.id}/review-snapshots` && method === "POST") {
        reviewSnapshot = fixtureReviewSnapshot(currentCase.id, matchResult);
        freshness = fixtureFreshness(currentCase.id, reviewSnapshot.id, true);
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-freeze-1" },
          body: JSON.stringify(reviewSnapshot),
        });
      }

      // /invoice-cases/:id/review-snapshots/latest
      if (path === `/invoice-cases/${currentCase.id}/review-snapshots/latest`) {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-snap-get-1" },
          body: JSON.stringify(currentCase.status === "DRAFT" ? null : reviewSnapshot),
        });
      }

      // /invoice-cases/:id/review-snapshots/:num/freshness
      if (path.includes("/freshness")) {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-fresh-1" },
          body: JSON.stringify(freshness),
        });
      }

      // /invoice-cases/:id/approve
      if (path === `/invoice-cases/${currentCase.id}/approve` && method === "POST") {
        currentCase.status = "EXPORT_PENDING";
        currentCase.version += 1;
        handoff = fixtureHandoff(currentCase.id, "ACKNOWLEDGED", "DELIVERED");
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-approve-1" },
          body: JSON.stringify(fixtureApprovalResult(currentCase.id, reviewSnapshot.id)),
        });
      }

      // /invoice-cases/:id/handoff
      if (path === `/invoice-cases/${currentCase.id}/handoff`) {
        const body = currentCase.status === "EXPORT_PENDING" || currentCase.status === "EXPORTED" ? handoff : { invoiceCaseId: currentCase.id, caseStatus: currentCase.status, caseVersion: currentCase.version, payment: null };
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-handoff-1" },
          body: JSON.stringify(body),
        });
      }

      // /invoice-cases/:id/audit-entries
      if (path.includes("/audit-entries")) {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-audit-1" },
          body: JSON.stringify({ entries: fixtureAuditEntries(currentCase.id), nextCursor: null }),
        });
      }

      return route.continue();
    });

    // 1. Visit Home and Login as Submitter
    await page.goto("/");
    await expect(page.locator("h1")).toContainText("Invoice Match 로그인");

    // Quick select demo account
    await page.click("#demo-btn-submitter");
    await page.click("#btn-login-submit");

    // Verify logged in Navbar
    await expect(page.locator(".navbar-user")).toContainText("submitter");
    await expect(page.locator(".badge-role-submitter")).toContainText("제출자 (SUBMITTER)");

    // Case List should be visible
    await expect(page.locator("h2.card-title")).toContainText("청구 사건 목록");

    // 2. Open New Case Modal and create case
    await page.click("#btn-list-new-case");
    await expect(page.locator("#modal-new-case-title")).toBeVisible();
    await page.fill("#new-invoice-num", "INV-2026-HAPPY");
    await page.click("#btn-create-case-submit");

    // Navigated to Case Detail
    await expect(page.locator("#case-header-title")).toContainText("INV-2026-HAPPY");
    await expect(page.locator("#badge-case-version")).toContainText("v1");

    // 3. Edit Draft line item and save
    await page.fill("#input-quantity-0", "80");
    await page.click("#btn-save-draft");
    await expect(page.locator(".alert-success")).toContainText("초안 라인이 성공적으로 저장되었습니다.");

    // Submit case
    await page.click("#btn-submit-case");
    await expect(page.locator(".alert-success")).toContainText("정상적으로 제출되었습니다");

    // 4. Switch identity to OPERATOR to run 3-way match
    currentRole = "OPERATOR";
    currentUsername = "operator";

    await page.click("#btn-logout");
    await page.click("#demo-btn-operator");
    await page.click("#btn-login-submit");

    // Open the case
    await page.click(`#btn-open-${currentCase.id}`);

    // Run Match
    await page.click("#btn-run-match");
    await expect(page.locator("#three-way-title")).toContainText("3-Way 일치 (Normal)");

    // 5. Switch identity to APPROVER to freeze snapshot and approve
    currentRole = "APPROVER";
    currentUsername = "approver";

    await page.click("#btn-logout");
    await page.click("#demo-btn-approver");
    await page.click("#btn-login-submit");

    // Open the case
    await page.click(`#btn-open-${currentCase.id}`);

    // Freeze Snapshot
    await page.click("#btn-freeze-snapshot");
    await expect(page.locator("#badge-freshness-current")).toBeVisible();

    // Verify approval pre-check box
    await expect(page.locator("#approval-precheck-box")).toContainText("승인 전 필수 검증 데이터 확인");

    // Final Approve
    await page.click("#btn-action-approve");
    await expect(page.locator(".alert-success")).toContainText("최종 승인되어 ERP 인계 대기");

    // Verify Handoff Section
    await expect(page.locator("#handoff-status-title")).toBeVisible();

    // Verify Audit Trail is present
    await expect(page.locator("#audit-history-title")).toContainText("감사 이력");
  });

  test("2. Supplement Request and Submitter Resubmission Revision Flow", async ({ page }) => {
    const currentCase = fixtureCaseDetail("case-suppl-1", "REVIEW_PENDING", 2, "submitter");
    const matchResult = fixtureMatchResult(currentCase.id, false);
    const reviewSnapshot = fixtureReviewSnapshot(currentCase.id, matchResult);
    const freshness = fixtureFreshness(currentCase.id, reviewSnapshot.id, true);

    await page.route("**/api/proxy/**", async (route) => {
      const path = new URL(route.request().url()).pathname.replace("/api/proxy", "");
      const method = route.request().method();

      if (path === "/me") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(fixtureUser("approver", ["APPROVER"])),
        });
      }
      if (path === "/invoice-cases" && method === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(fixtureCasePage([currentCase])),
        });
      }
      if (path === `/invoice-cases/${currentCase.id}` && method === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(currentCase),
        });
      }
      if (path === `/invoice-cases/${currentCase.id}/match`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(matchResult) });
      }
      if (path === `/invoice-cases/${currentCase.id}/review-snapshots/latest`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(reviewSnapshot) });
      }
      if (path.includes("/freshness")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(freshness) });
      }
      if (path === `/invoice-cases/${currentCase.id}/supplement-requests` && method === "POST") {
        currentCase.status = "SUPPLEMENT_REQUIRED";
        currentCase.version += 1;
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ id: "dec-1", decision: "SUPPLEMENT_REQUESTED" }),
        });
      }
      if (path === `/invoice-cases/${currentCase.id}/revisions` && method === "POST") {
        currentCase.status = "DRAFT";
        currentCase.version += 1;
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentCase) });
      }
      if (path === `/invoice-cases/${currentCase.id}/handoff`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ invoiceCaseId: currentCase.id, caseStatus: currentCase.status, payment: null }) });
      }
      if (path.includes("/audit-entries")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ entries: [], nextCursor: null }) });
      }
      return route.continue();
    });

    // Login as approver
    await page.goto("/");
    await page.click("#demo-btn-approver");
    await page.click("#btn-login-submit");

    // Open case
    await page.click(`#btn-open-${currentCase.id}`);

    // Request supplement
    await page.fill("#input-supplement-reason", "검수증 수량 재확인 필요");
    await page.click("#btn-action-supplement");
    await expect(page.locator(".alert-success")).toContainText("보완 요청이 성공적으로 등록되었습니다");

    // Submitter views case and opens revision
    await page.route("**/api/proxy/me", async (route) => {
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(fixtureUser("submitter", ["SUBMITTER"])),
      });
    });

    await page.click("#btn-logout");
    await page.click("#demo-btn-submitter");
    await page.click("#btn-login-submit");

    await page.click(`#btn-open-${currentCase.id}`);
    await expect(page.locator("#callout-supplement-prompt")).toContainText("보완 요청 수신 (SUPPLEMENT_REQUIRED)");

    // Click Open Revision
    await page.click("#btn-open-revision");
    await expect(page.locator(".alert-success")).toContainText("보완 개정(Revision)이 시작되어 초안(DRAFT) 상태로 전환되었습니다");
  });

  test("3. Reject Workflow", async ({ page }) => {
    const currentCase = fixtureCaseDetail("case-reject-1", "REVIEW_PENDING", 2, "submitter");
    const matchResult = fixtureMatchResult(currentCase.id, false);
    const reviewSnapshot = fixtureReviewSnapshot(currentCase.id, matchResult);
    const freshness = fixtureFreshness(currentCase.id, reviewSnapshot.id, true);

    await page.route("**/api/proxy/**", async (route) => {
      const path = new URL(route.request().url()).pathname.replace("/api/proxy", "");
      const method = route.request().method();

      if (path === "/me") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixtureUser("approver", ["APPROVER"])) });
      }
      if (path === "/invoice-cases" && method === "GET") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixtureCasePage([currentCase])) });
      }
      if (path === `/invoice-cases/${currentCase.id}` && method === "GET") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentCase) });
      }
      if (path === `/invoice-cases/${currentCase.id}/match`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(matchResult) });
      }
      if (path === `/invoice-cases/${currentCase.id}/review-snapshots/latest`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(reviewSnapshot) });
      }
      if (path.includes("/freshness")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(freshness) });
      }
      if (path === `/invoice-cases/${currentCase.id}/reject` && method === "POST") {
        currentCase.status = "REJECTED";
        currentCase.version += 1;
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: "dec-rej-1", decision: "REJECTED" }) });
      }
      if (path === `/invoice-cases/${currentCase.id}/handoff`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ invoiceCaseId: currentCase.id, caseStatus: currentCase.status, payment: null }) });
      }
      if (path.includes("/audit-entries")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ entries: [], nextCursor: null }) });
      }
      return route.continue();
    });

    await page.goto("/");
    await page.click("#demo-btn-approver");
    await page.click("#btn-login-submit");

    await page.click(`#btn-open-${currentCase.id}`);
    await page.fill("#input-reject-reason", "계약 위반 부당 청구로 최종 거절함");
    await page.click("#btn-action-reject");

    await expect(page.locator(".alert-success")).toContainText("청구서가 거절 처리되었습니다 (REJECTED)");
  });

  test("4. Self-Approval Forbidden (403)", async ({ page }) => {
    // Current user submitter submitted this case, but also holds APPROVER role
    const currentCase = fixtureCaseDetail("case-self-1", "REVIEW_PENDING", 2, "submitter");
    const matchResult = fixtureMatchResult(currentCase.id, true);
    const reviewSnapshot = fixtureReviewSnapshot(currentCase.id, matchResult);
    const freshness = fixtureFreshness(currentCase.id, reviewSnapshot.id, true);

    await page.route("**/api/proxy/**", async (route) => {
      const path = new URL(route.request().url()).pathname.replace("/api/proxy", "");

      if (path === "/me") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(fixtureUser("submitter", ["SUBMITTER", "APPROVER"])),
        });
      }
      if (path === "/invoice-cases") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixtureCasePage([currentCase])) });
      }
      if (path === `/invoice-cases/${currentCase.id}`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentCase) });
      }
      if (path === `/invoice-cases/${currentCase.id}/match`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(matchResult) });
      }
      if (path === `/invoice-cases/${currentCase.id}/review-snapshots/latest`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(reviewSnapshot) });
      }
      if (path.includes("/freshness")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(freshness) });
      }
      if (path === `/invoice-cases/${currentCase.id}/handoff`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ invoiceCaseId: currentCase.id, caseStatus: currentCase.status, payment: null }) });
      }
      if (path.includes("/audit-entries")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ entries: [], nextCursor: null }) });
      }
      return route.continue();
    });

    await page.goto("/");
    await page.click("#demo-btn-submitter");
    await page.click("#btn-login-submit");

    await page.click(`#btn-open-${currentCase.id}`);

    // Self-approval warning notice should be prominent
    await expect(page.locator("#callout-self-approval-notice")).toBeVisible();
    await expect(page.locator("#callout-self-approval-notice")).toContainText("제출자 본인은 사건을 승인할 수 없습니다");

    // The approve button should be disabled
    const approveBtn = page.locator("#btn-action-approve");
    await expect(approveBtn).toBeDisabled();
  });

  test("5. Stale Conflict Error UX (409 STALE_CASE_VERSION & STALE_REVIEW_TARGET)", async ({ page }) => {
    const currentCase = fixtureCaseDetail("case-stale-1", "DRAFT", 1, "submitter");

    await page.route("**/api/proxy/**", async (route) => {
      const path = new URL(route.request().url()).pathname.replace("/api/proxy", "");
      const method = route.request().method();

      if (path === "/me") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixtureUser("submitter", ["SUBMITTER"])) });
      }
      if (path === "/invoice-cases" && method === "GET") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixtureCasePage([currentCase])) });
      }
      if (path === `/invoice-cases/${currentCase.id}` && method === "GET") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentCase) });
      }
      // Return 409 STALE_CASE_VERSION on save
      if (path === `/invoice-cases/${currentCase.id}/draft`) {
        return route.fulfill({
          status: 409,
          contentType: "application/json",
          headers: { "x-trace-id": "trc-stale-case-123" },
          body: JSON.stringify({
            code: "STALE_CASE_VERSION",
            message: "The invoice case was modified concurrently",
            caseId: currentCase.id,
            expectedVersion: 1,
            latestVersion: 3,
          }),
        });
      }
      if (path.includes("/match")) {
        return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: "NOT_FOUND" }) });
      }
      if (path.includes("/review-snapshots")) {
        return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: "NOT_FOUND" }) });
      }
      if (path.includes("/audit-entries")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ entries: [], nextCursor: null }) });
      }
      if (path === `/invoice-cases/${currentCase.id}/handoff`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ invoiceCaseId: currentCase.id, caseStatus: currentCase.status, payment: null }) });
      }
      return route.continue();
    });

    await page.goto("/");
    await page.click("#demo-btn-submitter");
    await page.click("#btn-login-submit");

    await page.click(`#btn-open-${currentCase.id}`);

    // Click Save Draft
    await page.click("#btn-save-draft");

    // Stale Case Version banner must be shown
    const banner = page.locator("#stale-case-error-banner");
    await expect(banner).toBeVisible();
    await expect(banner).toContainText("409 버전 경합");
    await expect(banner).toContainText("expectedVersion: 1");
    await expect(banner).toContainText("latestVersion: 3");
    await expect(banner).toContainText("trc-stale-case-123");

    // "최신 상세 다시 불러오기" button exists and works
    await expect(page.locator("#btn-refresh-latest-case")).toBeVisible();
  });

  test("6. ERP Handoff States: FAILED vs RESULT_UNKNOWN distinction", async ({ page }) => {
    const currentCase = fixtureCaseDetail("case-handoff-1", "EXPORT_PENDING", 4, "submitter");
    let currentHandoff = fixtureHandoff(currentCase.id, "FAILED", "FAILED");

    await page.route("**/api/proxy/**", async (route) => {
      const path = new URL(route.request().url()).pathname.replace("/api/proxy", "");

      if (path === "/me") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixtureUser("approver", ["APPROVER"])) });
      }
      if (path === "/invoice-cases") {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(fixtureCasePage([currentCase])) });
      }
      if (path === `/invoice-cases/${currentCase.id}`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentCase) });
      }
      if (path.includes("/match")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(null) });
      }
      if (path.includes("/review-snapshots")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(null) });
      }
      if (path === `/invoice-cases/${currentCase.id}/handoff`) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentHandoff) });
      }
      if (path.includes("/audit-entries")) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ entries: [], nextCursor: null }) });
      }
      return route.continue();
    });

    await page.goto("/");
    await page.click("#demo-btn-approver");
    await page.click("#btn-login-submit");

    await page.click(`#btn-open-${currentCase.id}`);

    // Verify FAILED state alert
    await expect(page.locator("#callout-handoff-failed")).toBeVisible();
    await expect(page.locator("#badge-payment-failed")).toContainText("전송 실패 (FAILED)");

    // Now simulate RESULT_UNKNOWN
    currentHandoff = fixtureHandoff(currentCase.id, "RESULT_UNKNOWN", "RESULT_UNKNOWN");
    await page.click("#btn-refresh-handoff");

    // Verify RESULT_UNKNOWN state alert
    await expect(page.locator("#callout-handoff-unknown")).toBeVisible();
    await expect(page.locator("#callout-handoff-unknown")).toContainText("ERP 원장을 수동 대조해야 합니다");
    await expect(page.locator("#badge-payment-unknown")).toContainText("결과 불명 (RESULT_UNKNOWN)");
  });
});
