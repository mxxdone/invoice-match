import test from 'node:test';
import assert from 'node:assert/strict';
import { cn } from '../src/lib/utils.ts';

test('custom label size survives foreground color and replaces default control size', () => {
  assert.equal(cn('text-sm text-label text-foreground'), 'text-label text-foreground');
});

test('custom detail and label sizes conflict as sizes while preserving independent colors', () => {
  assert.equal(cn('text-detail text-foreground', 'text-label'), 'text-foreground text-label');
  assert.equal(cn('text-label text-muted-foreground', 'text-detail'), 'text-muted-foreground text-detail');
});
