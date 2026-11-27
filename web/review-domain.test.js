import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { reviewRow, canReview } from './review-domain.js';

test('review validation preserves decimals and rejects invalid periods', () => {
  const row = { unit_id: 'x', kind: 'cold', period_start: '2024-01-01', period_end: '2024-12-31', amount: '12,345' };
  assert.equal(reviewRow(row).value.amount, '12.345');
  assert.ok(reviewRow({ ...row, period_end: '2024-02-30' }).error);
  assert.ok(reviewRow({ ...row, period_start: '2025-01-01' }).error);
  assert.ok(reviewRow({ ...row, amount: '1.234,5' }).error);
  assert.ok(reviewRow({ ...row, unit_id: '' }).error);
});
test('permissions are explicit for the project and user', () => {
  const grants = [{ project_id: 'p', user_id: 'u', feature: 'water', permission: 'import' }];
  assert.equal(canReview(grants, 'p', 'u', 'import'), true);
  assert.equal(canReview(grants, 'p', 'u', 'edit'), false);
  assert.equal(canReview(grants, 'p', 'x', 'import'), false);
});
