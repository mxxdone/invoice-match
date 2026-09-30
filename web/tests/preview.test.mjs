import assert from 'node:assert/strict';
import test from 'node:test';
import { previewLines } from '../src/app/preview-data.ts';

test('normal design fixture balances each line and has unique line numbers', () => {
  const lines = previewLines('normal');
  assert.equal(lines.length, 8);
  assert.equal(new Set(lines.map(line => line.number)).size, lines.length);
  for (const line of lines) {
    assert.equal(line.issue, null);
    assert.equal(line.price, line.poPrice);
    assert.equal(line.planned, line.quantity);
    assert.ok(line.planned <= line.received);
  }
});
test('quantity fixture shows 40-unit shortage without overallocating', () => {
  const [line] = previewLines('quantity');
  assert.equal(line.quantity - line.received, 40);
  assert.equal(line.planned, 60);
  assert.ok(line.issue);
});
test('price and mapping examples preserve their distinct exception states', () => {
  const [price] = previewLines('price');
  assert.equal(price.price - price.poPrice, 1500);
  const [mapping] = previewLines('mapping');
  assert.equal(mapping.item, '');
  assert.equal(mapping.po, '');
  assert.equal(mapping.planned, 0);
});
test('changing one fixture cannot mutate another scenario', () => {
  const lines = previewLines('normal');
  lines[1].quantity = 999;
  assert.equal(previewLines('normal')[1].quantity, 12);
});
