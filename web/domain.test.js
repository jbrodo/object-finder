import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { canViewWater, formatAmount, formatDate, isPublicKey } from './domain.js';

test('water access requires enabled feature and an explicit user grant', () => {
  const features = [{ project_id: 'p', feature: 'water', enabled: true }];
  const grants = [{ project_id: 'p', user_id: 'u', feature: 'water', permission: 'view' }];
  assert.equal(canViewWater(features, grants, 'p', 'u'), true);
  assert.equal(canViewWater(features, grants, 'p', 'other'), false);
  assert.equal(canViewWater([{ ...features[0], enabled: false }], grants, 'p', 'u'), false);
});
test('configuration rejects secret and service role keys', () => {
  assert.equal(isPublicKey('sb_publishable_abc'), true);
  assert.equal(isPublicKey('sb_secret_abc'), false);
  assert.equal(isPublicKey('header.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.signature'), false);
});
test('data display preserves missing values and Italian formatting', () => {
  assert.equal(formatAmount(null), '—'); assert.equal(formatAmount('12.5'), '12,5');
  assert.equal(formatDate('2024-12-31'), '31/12/2024'); assert.equal(formatDate(null), '—');
});
