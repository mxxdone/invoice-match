import test from 'node:test';
import assert from 'node:assert/strict';
import { pageNumbers, previewCases, statusPresentation, suppliers } from '../src/app/cases/list-preview.ts';
test('preview supplier identity, state mapping and list fixture remain consistent',()=>{
  assert.equal(previewCases.length,48);
  assert.equal(new Set(previewCases.map(row=>row.id)).size,48);
  for(const row of previewCases){assert.ok(suppliers.find(s=>s.id===row.supplierId));assert.ok(statusPresentation[row.status]);}
  assert.equal(statusPresentation.REVIEW_PENDING.tone,statusPresentation.EXPORT_PENDING.tone);
  assert.notEqual(statusPresentation.EXPORTED.tone,statusPresentation.EXPORT_PENDING.tone);
});
test('pagination keeps first, current and last pages without unbounded buttons',()=>{
  assert.deepEqual(pageNumbers(1,1),[1]);
  assert.deepEqual(pageNumbers(2,3),[1,2,3]);
  assert.deepEqual(pageNumbers(7,13),[1,'gap-6',6,7,8,'gap-13',13]);
  assert.deepEqual(pageNumbers(13,13),[1,'gap-12',12,13]);
});
