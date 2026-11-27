import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

class Element {
  constructor() { this.hidden = false; this.disabled = false; this.value = ''; this.textContent = ''; this.children = []; this.events = {}; }
  addEventListener(name, fn) { this.events[name] = fn; }
  replaceChildren(...children) { this.children = children; }
  append(...children) { this.children.push(...children); }
  add(option) { this.children.push(option); if (this.children.length === 1) this.value = option.value; }
  setAttribute(name, value) { this[name] = value; }
}
const findText = element => [element.textContent, ...element.children.flatMap(child => findText(child))];
const settle = () => new Promise(resolve => setTimeout(resolve, 15));

test('sign in, load authorized consumption, sign out and deny unauthorized project', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'objectfinder-web-test-'));
  try {
    for (const file of ['app.js', 'domain.js', 'review.js', 'review-domain.js', 'package.json']) await writeFile(join(dir, file), await readFile(new URL(file, import.meta.url)));
    await writeFile(join(dir, 'config.js'), "export const supabaseUrl='https://test.supabase.co'; export const supabaseAnonKey='public-key';");
    const ids = ['login-panel', 'dashboard', 'dashboard-main', 'review-panel', 'open-review', 'logout', 'account', 'notice', 'results', 'count', 'project', 'building', 'kind', 'unit', 'period', 'scroll-hint', 'password', 'email', 'login-form', 'login-button', 'login-status', 'login-error'];
    const elements = Object.fromEntries(ids.map(id => [id, new Element()]));
    elements.kind.value = 'all'; elements.email.value = 'test@example.com'; elements.password.value = 'password';
    globalThis.document = { getElementById: id => elements[id], createElement: () => new Element() };
    globalThis.Option = class { constructor(name, value) { this.textContent = name; this.value = value; } };
    let allow = true, failProjects = true, failLogin = true;
    globalThis.fetch = async (url, options) => {
      const path = new URL(url).pathname;
      if (path === '/auth/v1/token') {
        if (failLogin) { failLogin = false; return { ok: false, status: 400 }; }
        return { ok: true, json: async () => ({ access_token: 'token', user: { id: 'u', email: 'test@example.com' } }) };
      }
      if (path === '/auth/v1/logout') return { ok: true };
      assert.equal(options.headers.Authorization, 'Bearer token');
      if (path === '/rest/v1/projects' && failProjects) { failProjects = false; throw new Error('Rete non disponibile'); }
      const data = {
        '/rest/v1/projects': [{ id: 'p', name: 'Casa' }],
        '/rest/v1/project_features': [{ project_id: 'p', feature: 'water', enabled: true }],
        '/rest/v1/project_feature_grants': allow ? [{ project_id: 'p', user_id: 'u', feature: 'water', permission: 'view' }, { project_id: 'p', user_id: 'u', feature: 'water', permission: 'import' }] : [],
        '/rest/v1/buildings': [{ id: 'b', name: 'Edificio' }],
        '/rest/v1/water_units': [{ id: 'unit', label: 'A1', section: '' }],
        '/rest/v1/water_consumption': [{ id: 'row', unit_id: 'unit', kind: 'cold', period_start: '2024-01-01', period_end: '2024-12-31', amount: '12.5', import_row_id: 'source', updated_at: '2025-01-02T12:00:00Z' }],
        '/rest/v1/water_import_rows': [{ id: 'source', import_id: 'imp', source_row: 'Foglio 1!E2' }],
        '/rest/v1/water_imports': [{ id: 'imp', source_name: 'prospetto.xlsx', source_type: 'xlsx' }],
      }[path];
      assert.ok(data, path);
      return { ok: true, headers: { get: () => `0-${Math.max(0, data.length - 1)}/${data.length}` }, json: async () => data };
    };
    await import(pathToFileURL(join(dir, 'app.js')));
    elements['login-form'].events.submit({ preventDefault() {}, submitter: new Element() });
    assert.equal(elements['login-status'].hidden, false);
    assert.equal(elements['login-button'].textContent, 'Accesso in corso…');
    await settle();
    assert.equal(elements['login-error'].hidden, false);
    assert.match(elements['login-error'].textContent, /Controlla email e password/);
    elements['login-form'].events.submit({ preventDefault() {}, submitter: new Element() });
    await settle();
    assert.equal(elements.dashboard.hidden, false);
    assert.ok(findText(elements.results).includes('Impossibile caricare i dati'));
    elements.results.children[0].children[2].events.click();
    await settle();
    assert.ok(findText(elements.results).includes('12,5'));
    assert.ok(findText(elements.results).includes('A1'));
    assert.ok(findText(elements.results).includes('prospetto.xlsx'));
    assert.ok(findText(elements.results).includes('Foglio 1!E2'));
    elements.kind.value = 'hot'; elements.kind.events.change();
    assert.ok(findText(elements.results).includes('Nessun risultato per questi filtri'));
    elements.results.children[0].children[2].events.click();
    assert.ok(findText(elements.results).includes('12,5'));
    await elements.logout.events.click();
    assert.equal(elements.dashboard.hidden, true);
    assert.equal(elements.results.children.length, 0);
    allow = false; elements.password.value = 'password';
    elements['login-form'].events.submit({ preventDefault() {}, submitter: new Element() });
    await settle();
    assert.ok(findText(elements.results).includes('Consumi non disponibili'));
  } finally {
    delete globalThis.document; delete globalThis.Option; delete globalThis.fetch;
    await rm(dir, { recursive: true, force: true });
  }
});
