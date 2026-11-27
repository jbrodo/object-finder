import { canViewWater, formatAmount, formatDate, isPublicKey } from './domain.js';
import { createReview } from './review.js';
import { canReview } from './review-domain.js';

const $ = id => document.getElementById(id);
const state = { token: null, user: null, projects: [], buildings: [], features: [], grants: [], rows: [], units: [], sourceRows: [], imports: [], generation: 0 };
const review = createReview({ root: $('review-panel'), context: () => ({ projectId: $('project').value, buildingId: $('building').value,
  userId: state.user.id, grants: state.grants }), allRows, request, close: () => {
    $('review-panel').hidden = true; $('dashboard-main').hidden = false; run(loadConsumption);
  } });
let config;
try { config = await import('./config.js'); } catch { config = null; }

function notify(message = '') { $('notice').textContent = message; $('notice').hidden = !message; }
function showAuth(signedIn) {
  $('login-panel').hidden = signedIn; $('dashboard').hidden = !signedIn; $('logout').hidden = !signedIn;
  $('account').textContent = signedIn ? state.user?.email || '' : '';
}
function setEmpty(title, message, action) {
  const box = document.createElement('div'); box.className = 'empty';
  const heading = document.createElement('strong'); heading.textContent = title;
  const description = document.createElement('span'); description.textContent = message;
  box.append(heading, description);
  if (action) { const button = document.createElement('button'); button.className = 'button text empty-action'; button.textContent = action.label; button.addEventListener('click', action.run); box.append(button); }
  $('results').replaceChildren(box); $('count').textContent = ''; $('scroll-hint').hidden = true;
}
function populate(select, entries, emptyLabel) {
  select.replaceChildren();
  if (!entries.length) { const option = new Option(emptyLabel, ''); select.add(option); select.disabled = true; return; }
  for (const entry of entries) select.add(new Option(entry.name, entry.id));
  select.disabled = false;
}
function setFiltersReady(ready) { for (const id of ['kind', 'unit', 'period']) $(id).disabled = !ready; }
function validConfig() {
  try {
    const url = new URL(config?.supabaseUrl);
    return (url.protocol === 'https:' || (url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
      && !url.username && !url.password && !url.search && !url.hash && (url.pathname === '/' || url.pathname === '')
      && isPublicKey(config?.supabaseAnonKey);
  } catch { return false; }
}
async function request(path, options = {}, includeCount = false) {
  const base = config.supabaseUrl.replace(/\/$/, '');
  const response = await fetch(base + path, { ...options, redirect: 'error', headers: {
    apikey: config.supabaseAnonKey, ...(state.token ? { Authorization: `Bearer ${state.token}` } : {}), ...options.headers,
  } });
  if (!response.ok) {
    if (response.status === 401 && state.token) { signOut(); throw new Error('Sessione scaduta. Accedi di nuovo.'); }
    throw new Error(path.startsWith('/auth/') && (response.status === 401 || response.status === 400)
      ? 'Accesso non riuscito. Controlla email e password.' : 'Non è stato possibile caricare i dati. Riprova.');
  }
  const data = await response.json();
  if (!includeCount) return data;
  const count = response.headers.get('content-range')?.match(/\/(\d+)$/);
  if (!count) throw new Error('Impossibile verificare che tutti i dati siano stati caricati.');
  return { data, total: Number(count[1]) };
}
async function allRows(table, filters = {}) {
  const result = [];
  let offset = 0;
  while (offset < 20000) {
    const query = new URLSearchParams({ ...filters, limit: '500', offset: String(offset) });
    const { data, total } = await request(`/rest/v1/${table}?${query}`, { headers: { Prefer: 'count=exact' } }, true);
    if (!Array.isArray(data)) throw new Error('Risposta del server non valida.');
    result.push(...data); offset += data.length;
    if (offset >= total) return result;
    if (!data.length) throw new Error('Impossibile completare il caricamento dei dati.');
  }
  throw new Error('Questo edificio ha troppi dati da caricare. Seleziona un altro edificio o contatta un amministratore.');
}
function signOut() {
  review.invalidate(); $('review-panel').hidden = true; $('dashboard-main').hidden = false; $('open-review').hidden = true;
  state.generation++; Object.assign(state, { token: null, user: null, projects: [], buildings: [], features: [], grants: [], rows: [], units: [], sourceRows: [], imports: [] });
  $('password').value = ''; $('account').textContent = ''; $('results').replaceChildren(); showAuth(false);
}
async function loadProjects() {
  const generation = ++state.generation;
  setEmpty('Caricamento in corso', 'Stiamo cercando i tuoi progetti.');
  const [projects, features, grants] = await Promise.all([
    allRows('projects', { select: 'id,name', order: 'name.asc,id.asc' }),
    allRows('project_features', { select: 'project_id,feature,enabled', feature: 'eq.water', order: 'project_id.asc' }),
    allRows('project_feature_grants', { select: 'project_id,user_id,feature,permission', feature: 'eq.water', user_id: `eq.${state.user.id}`, order: 'project_id.asc,permission.asc' }),
  ]);
  if (generation !== state.generation) return;
  Object.assign(state, { projects, features, grants }); populate($('project'), projects, 'Nessun progetto');
  if (!projects.length) { populate($('building'), [], 'Nessun edificio'); setEmpty('Nessun progetto', 'Crea un progetto nell’app Object Finder per iniziare.'); return; }
  await loadBuildings();
}
async function loadBuildings() {
  const generation = ++state.generation; const projectId = $('project').value;
  setFiltersReady(false);
  $('open-review').hidden = true;
  state.rows = []; state.units = []; state.buildings = []; state.sourceRows = []; state.imports = [];
  populate($('building'), [], 'Caricamento…');
  if (!canViewWater(state.features, state.grants, projectId, state.user.id)) {
    populate($('building'), [], 'Non disponibile'); setEmpty('Consumi non disponibili', 'La funzione non è attiva o non hai il permesso di consultarla. Contatta un amministratore del progetto.'); return;
  }
  setEmpty('Caricamento in corso', 'Stiamo cercando gli edifici del progetto.');
  const buildings = await allRows('buildings', { select: 'id,name', project_id: `eq.${projectId}`, order: 'name.asc,id.asc' });
  if (generation !== state.generation) return;
  state.buildings = buildings; populate($('building'), buildings, 'Nessun edificio');
  if (!buildings.length) { setEmpty('Nessun edificio', 'Aggiungi un edificio al progetto per consultare i consumi.'); return; }
  await loadConsumption();
}
async function loadConsumption() {
  const generation = ++state.generation; const projectId = $('project').value; const buildingId = $('building').value;
  if (!buildingId) return;
  setFiltersReady(false);
  state.rows = []; state.units = []; setEmpty('Caricamento in corso', 'Stiamo leggendo i consumi registrati.');
  const filters = { project_id: `eq.${projectId}`, building_id: `eq.${buildingId}` };
  const [units, rows] = await Promise.all([
    allRows('water_units', { ...filters, select: 'id,label,section', order: 'id.asc' }),
    allRows('water_consumption', { ...filters, select: 'id,unit_id,kind,period_start,period_end,amount,import_row_id,updated_at', order: 'period_start.desc,id.asc' }),
  ]);
  if (generation !== state.generation) return;
  const canReadSource = state.grants.some(g => g.project_id === projectId && g.user_id === state.user.id && g.feature === 'water' && g.permission === 'import');
  let sourceRows = [], imports = [];
  if (canReadSource && rows.some(row => row.import_row_id)) {
    try {
      [sourceRows, imports] = await Promise.all([
        allRows('water_import_rows', { ...filters, select: 'id,import_id,source_row', order: 'id.asc' }),
        allRows('water_imports', { ...filters, select: 'id,source_name,source_type,confirmed_at', order: 'id.asc' }),
      ]);
    } catch { sourceRows = []; imports = []; }
  }
  if (generation !== state.generation || !state.token) return;
  Object.assign(state, { units, rows, sourceRows, imports });
  $('open-review').hidden = !canReview(state.grants, projectId, state.user.id, 'import');
  populateFilters(); setFiltersReady(rows.length > 0); renderRows();
}
function populateFilters() {
  const units = state.units.slice().sort((a, b) => `${a.section} ${a.label}`.localeCompare(`${b.section} ${b.label}`, 'it', { numeric: true }));
  populate($('unit'), [{ id: 'all', name: 'Tutti' }, ...units.map(unit => ({ id: unit.id, name: [unit.section, unit.label].filter(Boolean).join(' · ') }))], 'Tutti');
  const values = [...new Set(state.rows.map(row => `${row.period_start}/${row.period_end}`))].sort().reverse();
  populate($('period'), [{ id: 'all', name: 'Tutti' }, ...values.map(value => { const [start, end] = value.split('/'); return { id: value, name: `${formatDate(start)} – ${formatDate(end)}` }; })], 'Tutti');
}
function resetFilters() { $('kind').value = 'all'; $('unit').value = 'all'; $('period').value = 'all'; renderRows(); }
function renderRows() {
  const rows = state.rows.filter(row => ($('kind').value === 'all' || row.kind === $('kind').value)
    && ($('unit').value === 'all' || row.unit_id === $('unit').value)
    && ($('period').value === 'all' || `${row.period_start}/${row.period_end}` === $('period').value));
  if (!rows.length) {
    if (state.rows.length) setEmpty('Nessun risultato per questi filtri', 'Cambia la selezione o mostra tutti i consumi.', { label: 'Mostra tutti', run: resetFilters });
    else setEmpty('Nessun consumo registrato', 'I dati confermati per questo edificio compariranno qui.');
    return;
  }
  const unitMap = new Map(state.units.map(unit => [unit.id, unit]));
  const sourceMap = new Map(state.sourceRows.map(row => [row.id, row]));
  const imports = new Map(state.imports.map(item => [item.id, item]));
  const wrap = document.createElement('div'); wrap.className = 'table-wrap';
  wrap.tabIndex = 0; wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', 'Tabella dei consumi, scorribile orizzontalmente');
  const table = document.createElement('table');
  const head = document.createElement('thead'); const heading = document.createElement('tr');
  for (const label of ['Appartamento', 'Acqua', 'Periodo', 'Consumo (m³)', 'Origine']) { const th = document.createElement('th'); th.scope = 'col'; th.textContent = label; heading.append(th); }
  head.append(heading); table.append(head);
  const body = document.createElement('tbody');
  for (const row of rows) {
    const tr = document.createElement('tr'); const unit = unitMap.get(row.unit_id);
    const unitLabel = unit ? [unit.section, unit.label].filter(Boolean).join(' · ') : 'Unità non disponibile';
    for (const text of [unitLabel, row.kind === 'cold' ? 'Fredda' : row.kind === 'hot' ? 'Calda' : 'Non specificata', `${formatDate(row.period_start)} – ${formatDate(row.period_end)}`, formatAmount(row.amount)]) {
      const td = document.createElement('td'); td.textContent = text; tr.append(td);
    }
    const source = document.createElement('td');
    const sourceRow = sourceMap.get(row.import_row_id); const sourceImport = sourceRow && imports.get(sourceRow.import_id);
    const title = document.createElement('span');
    title.textContent = !row.import_row_id ? 'Non registrata' : sourceImport ? sourceImport.source_name : 'Non disponibile';
    source.append(title);
    if (sourceImport && sourceRow) { const detail = document.createElement('small'); detail.textContent = sourceRow.source_row; source.append(detail); }
    if (row.updated_at) { const updated = document.createElement('small'); updated.textContent = `Aggiornato il ${formatDate(row.updated_at.slice(0, 10))}`; source.append(updated); }
    tr.append(source);
    body.append(tr);
  }
  table.append(body); wrap.append(table); $('results').replaceChildren(wrap);
  $('scroll-hint').hidden = false;
  $('count').textContent = `${rows.length} ${rows.length === 1 ? 'voce' : 'voci'}`;
}
async function run(action) { notify(); try { await action(); } catch (error) {
  if (state.token) setEmpty('Impossibile caricare i dati', error.message || 'Controlla la connessione e riprova.', { label: 'Riprova', run: () => run(action) });
  else notify(error.message || 'Operazione non riuscita. Riprova.');
} }
$('login-form').addEventListener('submit', event => {
  event.preventDefault(); notify(); $('login-error').hidden = true;
  (async () => {
    if (!validConfig()) throw new Error('La connessione non è configurata. Contatta chi gestisce l’app.');
    const button = $('login-button'); button.disabled = true; button.textContent = 'Accesso in corso…';
    $('login-status').hidden = false; $('login-status').textContent = 'Accesso in corso.';
    try {
      const session = await request('/auth/v1/token?grant_type=password', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: $('email').value.trim(), password: $('password').value }) });
      if (!session.access_token || !session.user?.id) throw new Error('Accesso non riuscito. Riprova.');
      state.token = session.access_token; state.user = session.user; $('password').value = ''; showAuth(true); await run(loadProjects);
    } finally { button.disabled = false; button.textContent = 'Accedi'; $('login-status').hidden = true; $('login-status').textContent = ''; }
  })().catch(error => { $('login-error').textContent = error.message || 'Accesso non riuscito. Riprova.'; $('login-error').hidden = false; });
});
$('logout').addEventListener('click', async () => {
  if (review.isBusy()) { notify('Attendi il termine dell’operazione prima di uscire.'); return; }
  if (review.hasUnsaved() && !globalThis.confirm('Ci sono modifiche non salvate. Vuoi uscire?')) return;
  const token = state.token; signOut(); notify();
  if (!token) return;
  try {
    const response = await fetch(config.supabaseUrl.replace(/\/$/, '') + '/auth/v1/logout?scope=local', {
      method: 'POST', redirect: 'error', headers: { apikey: config.supabaseAnonKey, Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error();
  } catch { notify('Sei uscito da questa pagina. Non è stato possibile terminare la sessione sul server.'); }
});
$('project').addEventListener('change', () => run(loadBuildings));
$('building').addEventListener('change', () => run(loadConsumption));
$('kind').addEventListener('change', renderRows);
$('unit').addEventListener('change', renderRows);
$('period').addEventListener('change', renderRows);
$('open-review').addEventListener('click', () => { $('dashboard-main').hidden = true; $('review-panel').hidden = false; review.open(); });
globalThis.addEventListener?.('beforeunload', event => { if (review.hasUnsaved() || review.isBusy()) event.preventDefault(); });
if (!validConfig()) notify('La connessione non è configurata. Contatta chi gestisce l’app.');
