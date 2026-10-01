import test from 'node:test';
import assert from 'node:assert/strict';
import { draftLinePayload, enteredDraftLines, isUnusedDraftLine, isValidDraftLine } from '../src/app/cases/draft-line-model.ts';

const empty = id => ({ id, name: '', quantity: 1, price: 0, item: '' });
const complete = id => ({ ...empty(id), name: '복사용지', quantity: 5, price: 2500, edited: true });

test('untouched rows anywhere are excluded and payload line numbers stay consecutive', () => {
  const rows = [empty(1), complete(2), empty(3), complete(4), empty(5)];
  assert.equal(enteredDraftLines(rows).length, 2);
  assert.deepEqual(draftLinePayload(rows), [1, 2].map(lineNumber => ({ lineNumber, rawItemName: '복사용지', quantity: 5, unitPrice: 2500, confirmedItemId: null })));
  assert.ok(enteredDraftLines(rows).every(isValidDraftLine));
});

test('only empty rows leave no submittable lines', () => {
  assert.equal(enteredDraftLines([empty(1), empty(2), empty(3)]).length, 0);
});

test('partially entered or edited back to defaults rows cannot silently disappear', () => {
  for (const row of [{ ...empty(1), edited: true }, { ...empty(1), item: 'ITEM-A' }, { ...empty(1), quantity: 2 }, { ...empty(1), price: 10 }]) {
    assert.equal(isUnusedDraftLine(row), false);
    assert.equal(isValidDraftLine(row), false);
    assert.equal(enteredDraftLines([complete(2), row]).length, 2);
  }
});

test('zero price is allowed for a named line and persisted rows are retained', () => {
  assert.equal(isValidDraftLine({ ...complete(1), price: 0 }), true);
  assert.equal(isUnusedDraftLine({ ...empty(1), edited: true }), false);
});

test('invalid quantities and prices remain invalid rather than being filtered out', () => {
  for (const quantity of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(isValidDraftLine({ ...complete(1), quantity }), false);
  }
  for (const price of [-1, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(isValidDraftLine({ ...complete(1), price }), false);
  }
});
