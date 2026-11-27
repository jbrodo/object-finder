import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createReview } from './review.js';

class Node {
  constructor(tag = '') { this.tagName = tag; this.children = []; this.events = {}; this.attributes = {}; this.value = ''; this.textContent = ''; this.checked = false; this.disabled = false; }
  append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } }
  replaceChildren(...children) { this.children = []; this.append(...children); }
  add(child) { this.append(child); if (this.children.length === 1) this.value = child.value; }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener(name, handler) { this.events[name] = handler; }
  querySelector(selector) { return walk(this).find(node => selector === '[data-review-status]' && Object.hasOwn(node.attributes, 'data-review-status')) || null; }
  remove() { this.parent.children = this.parent.children.filter(child => child !== this); }
}
function walk(node) { return [node, ...node.children.flatMap(walk)]; }
const byText = (root, text) => walk(root).find(node => node.textContent === text);

test('review keeps confirmation disabled until the corrected row is saved and checked', async () => {
  globalThis.document = { createElement: tag => new Node(tag) };
  globalThis.Option = class extends Node { constructor(text, value) { super('option'); this.textContent = text; this.value = value; } };
  globalThis.confirm = () => true;
  try {
    const root = new Node('section'); const requests = []; let failConfirm = true;
    const row = { id: 'r', source_row: 'Foglio!E2', source_unit_label: 'A1', source_period_label: '2024', source_amount: '12,5', source_warnings: [], unit_id: 'u1', kind: 'cold', period_start: '2024-01-01', period_end: '2024-12-31', amount: 12.5 };
    const review = createReview({ root, close() {}, context: () => ({ projectId: 'p', buildingId: 'b', userId: 'user', grants: [
      { project_id: 'p', user_id: 'user', feature: 'water', permission: 'edit' },
    ] }), allRows: async table => ({
      water_imports: [{ id: 'd', source_name: 'foto.jpg', created_by: 'user', confirmed_at: null }],
      water_units: [{ id: 'u1', label: 'A1', section: '' }], water_import_rows: [row],
    })[table], request: async (path, options) => {
      requests.push({ path, options });
      if (path.includes('/rpc/') && failConfirm) { failConfirm = false; throw new Error('conflict'); }
      return path.includes('/rpc/') ? 1 : [row];
    } });
    await review.open();
    await byText(root, 'foto.jpg').events.click();
    const form = walk(root).find(node => node.tagName === 'form' && node.className === 'review-row');
    const confirm = byText(root, 'Conferma importazione');
    const checkbox = walk(form).find(node => node.type === 'checkbox');
    const amount = walk(form).find(node => node.inputMode === 'decimal');
    assert.equal(confirm.disabled, true);
    amount.value = '12,6'; amount.events.input();
    checkbox.checked = true; checkbox.events.change();
    assert.equal(checkbox.checked, false);
    await form.events.submit({ preventDefault() {} });
    checkbox.checked = true; checkbox.events.change();
    assert.equal(confirm.disabled, false);
    await confirm.events.click();
    assert.match(walk(root).find(node => node.attributes['data-review-status'] !== undefined).textContent, /Impossibile confermare/);
    assert.equal(confirm.disabled, false);
    await confirm.events.click();
    assert.equal(requests[0].options.method, 'PATCH');
    const confirmation = JSON.parse(requests[1].options.body);
    assert.equal(confirmation.p_rows[0].amount, '12.6');
    assert.ok(byText(root, 'Importazione confermata'));
  } finally { delete globalThis.document; delete globalThis.Option; delete globalThis.confirm; }
});

test('another member’s draft is displayed without edit or confirmation controls', async () => {
  globalThis.document = { createElement: tag => new Node(tag) };
  globalThis.Option = class extends Node { constructor(text, value) { super('option'); this.textContent = text; this.value = value; } };
  try {
    const root = new Node('section');
    const review = createReview({ root, close() {}, context: () => ({ projectId: 'p', buildingId: 'b', userId: 'viewer', grants: [] }),
      allRows: async table => ({ water_imports: [{ id: 'd', source_name: 'altra.xlsx', created_by: 'owner', confirmed_at: null }],
        water_units: [{ id: 'u1', label: 'A1', section: '' }], water_import_rows: [{ id: 'r', source_row: 'A1' }] })[table],
      request: async () => { throw new Error('unexpected mutation'); } });
    await review.open(); await byText(root, 'altra.xlsx').events.click();
    assert.equal(byText(root, 'Conferma importazione').disabled, true);
    assert.equal(byText(root, 'Salva riga').disabled, true);
    assert.ok(byText(root, 'Solo chi ha creato questa bozza può modificarla o confermarla.'));
  } finally { delete globalThis.document; delete globalThis.Option; }
});
