import assert from "node:assert/strict";
import test from "node:test";
import { generateRequestId, generateTraceId } from "../src/lib/id-generator.ts";
import {
  translateStaleReason,
  translateExceptionType,
  translateAuditAction,
} from "../src/lib/translators.ts";

test("id-generator generates unique IDs with expected prefixes", () => {
  const req1 = generateRequestId("custom");
  const req2 = generateRequestId("custom");
  assert.ok(req1.startsWith("custom-"));
  assert.notEqual(req1, req2);

  const trc1 = generateTraceId();
  const trc2 = generateTraceId();
  assert.ok(trc1.startsWith("trc-"));
  assert.notEqual(trc1, trc2);
});

test("translateStaleReason produces clear Korean descriptions for all 409 stale reasons", () => {
  const reasons = [
    "CASE_STATE",
    "CASE_VERSION",
    "EVIDENCE_BUNDLE",
    "MATCH_RESULT",
    "MAPPING",
    "PURCHASING_SNAPSHOT",
    "SUPERSEDED",
  ];

  for (const reason of reasons) {
    const translated = translateStaleReason(reason);
    assert.ok(translated.length > 5, `Reason ${reason} should have detailed Korean translation`);
    assert.notEqual(translated, reason);
  }
});

test("translateExceptionType translates 3-way match exception codes", () => {
  const types = [
    "ITEM_UNCONFIRMED",
    "PO_LINE_NOT_FOUND",
    "PO_LINE_AMBIGUOUS",
    "UNIT_PRICE_MISMATCH",
    "QUANTITY_EXCEEDS_RECEIPT_BALANCE",
    "DUPLICATE_INVOICE_SUSPECTED",
  ];

  for (const type of types) {
    const text = translateExceptionType(type);
    assert.ok(text.includes("("), `Exception ${type} should have detailed explanation`);
  }
});

test("translateAuditAction translates audit event actions to Korean", () => {
  const actions = ["CREATE", "DRAFT_REPLACE", "SUBMIT", "MATCH", "APPROVE", "REJECT"];
  for (const act of actions) {
    const text = translateAuditAction(act);
    assert.ok(text.includes(act));
  }
});
