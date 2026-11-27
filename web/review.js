import { canReview, reviewRow } from './review-domain.js';

const el = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
};
const uuidFilter = id => `eq.${id}`;

export function createReview({ root, context, allRows, request, close }) {
  let epoch = 0, draft = null, rows = [], units = [], dirty = new Set(), checked = new Set(), busy = false;
  let contextSnapshot;

  function hasUnsaved() { return dirty.size > 0; }
  function leave() {
    if (busy) { status('Attendi il termine dell’operazione.'); return false; }
    if (hasUnsaved() && !globalThis.confirm('Ci sono modifiche non salvate. Vuoi uscire dalla revisione?')) return false;
    epoch++; dirty.clear(); checked.clear(); root.replaceChildren(); close(); return true;
  }
  function status(text, error = false) {
    const target = root.querySelector('[data-review-status]');
    if (target) { target.textContent = text; target.className = error ? 'review-status error' : 'review-status'; }
  }
  function makeButton(text, handler, className = 'button outlined') {
    const button = el('button', text, className); button.type = 'button'; button.addEventListener('click', handler); return button;
  }
  function createField(labelText, input) {
    const label = el('label', labelText); label.append(input); return label;
  }
  function input(type, value) {
    const field = el('input'); field.type = type; field.value = value ?? ''; field.required = true; return field;
  }
  function select(options, value) {
    const field = el('select');
    for (const option of options) field.add(new Option(option.label, option.value));
    field.value = value || ''; field.required = true; return field;
  }
  function clearRoot(title, description) {
    root.replaceChildren();
    root.append(makeButton('Torna ai consumi', leave, 'button text review-back'));
    root.append(el('h1', title));
    root.append(el('p', description, 'review-lead'));
    const message = el('p', '', 'review-status'); message.setAttribute('role', 'status'); message.setAttribute('aria-live', 'polite'); message.setAttribute('data-review-status', ''); root.append(message);
  }
  async function open() {
    const current = ++epoch; contextSnapshot = context(); draft = null; dirty.clear(); checked.clear();
    clearRoot('Importazioni da rivedere', 'Scegli una bozza dell’edificio selezionato.'); status('Caricamento delle importazioni…');
    try {
      const filter = { project_id: uuidFilter(contextSnapshot.projectId), building_id: uuidFilter(contextSnapshot.buildingId) };
      const [items, allUnits] = await Promise.all([
        allRows('water_imports', { ...filter, select: 'id,source_name,source_type,created_by,created_at,confirmed_at', order: 'created_at.desc,id.asc' }),
        allRows('water_units', { ...filter, select: 'id,label,section', order: 'id.asc' }),
      ]);
      if (current !== epoch) return;
      units = allUnits; status('');
      const pending = items.filter(item => !item.confirmed_at);
      if (!pending.length) { root.append(el('div', 'Nessuna importazione da rivedere per questo edificio.', 'empty')); return; }
      const list = el('ul', null, 'draft-list');
      for (const item of pending) {
        const entry = el('li'); const button = makeButton(item.source_name, () => loadDraft(item), 'draft-link');
        entry.append(button, el('span', item.created_by === contextSnapshot.userId ? 'Da rivedere' : 'Creata da un altro membro · sola lettura', 'draft-meta')); list.append(entry);
      }
      root.append(list);
    } catch { if (current === epoch) status('Impossibile caricare le importazioni. Torna ai consumi e riprova.', true); }
  }
  async function loadDraft(item) {
    const current = ++epoch; draft = item; dirty.clear(); checked.clear();
    clearRoot(item.source_name, 'Confronta i dati estratti con il documento originale prima di confermare. Il documento non è ancora disponibile in questa schermata.');
    status('Caricamento delle righe…');
    try {
      const fetched = await allRows('water_import_rows', { import_id: uuidFilter(item.id), project_id: uuidFilter(contextSnapshot.projectId),
        building_id: uuidFilter(contextSnapshot.buildingId), select: 'id,source_row,source_unit_label,source_period_label,source_amount,source_warnings,unit_id,kind,period_start,period_end,amount', order: 'source_row.asc,id.asc' });
      if (current !== epoch) return;
      rows = fetched; status(''); renderDraft();
    } catch { if (current === epoch) status('Impossibile caricare le righe. Torna all’elenco e riprova.', true); }
  }
  function renderDraft() {
    const editable = draft.created_by === contextSnapshot.userId;
    const mayConfirm = editable && canReview(contextSnapshot.grants, contextSnapshot.projectId, contextSnapshot.userId, 'edit');
    root.append(makeButton('Tutte le importazioni', () => {
      if (busy) { status('Attendi il termine dell’operazione.'); return; }
      if (!hasUnsaved() || globalThis.confirm('Ci sono modifiche non salvate. Vuoi tornare all’elenco?')) open();
    }, 'button text review-list-back'));
    if (!editable) root.append(el('p', 'Solo chi ha creato questa bozza può modificarla o confermarla.', 'review-info'));
    else if (!mayConfirm) root.append(el('p', 'Puoi rivedere la bozza, ma per confermare serve anche il permesso di modifica dei consumi.', 'review-info'));
    if (!units.length) root.append(el('p', 'Non ci sono appartamenti in questo edificio. Aggiungine uno per completare la revisione.', 'review-info'));
    if (mayConfirm) renderCreateUnit();
    const list = el('div', null, 'review-rows');
    if (!rows.length) list.append(el('p', 'Questa bozza non contiene righe. Non può essere confermata.', 'empty'));
    for (const row of rows) list.append(renderRow(row, editable));
    root.append(list);
    const footer = el('div', null, 'review-footer');
    const help = el('p', 'Salva le modifiche e conferma di aver controllato ogni riga.', 'review-info');
    const confirmButton = makeButton('Conferma importazione', async () => {
      if (busy || dirty.size || checked.size !== rows.length || !rows.length) return;
      busy = true; syncConfirm(); status('Conferma in corso…');
      try {
        const snapshot = rows.map(({ id, unit_id, kind, period_start, period_end, amount }) => ({ id, unit_id, kind, period_start, period_end, amount }));
        await request('/rest/v1/rpc/confirm_water_import_reviewed', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ p_id: draft.id, p_rows: snapshot }) });
        dirty.clear(); checked.clear(); clearRoot('Importazione confermata', 'I consumi sono stati registrati.');
        root.append(makeButton('Torna ai consumi', () => { epoch++; close(); }, 'button primary'));
      } catch { status('Impossibile confermare. Controlla duplicati, permessi o modifiche in un’altra scheda; poi ricarica la bozza e riprova.', true); }
      finally { busy = false; syncConfirm(); }
    }, 'button primary');
    confirmButton.setAttribute('data-confirm-import', '');
    footer.append(help, confirmButton); root.append(footer);
    function syncConfirm() {
      confirmButton.disabled = !mayConfirm || busy || !rows.length || dirty.size > 0 || checked.size !== rows.length;
    }
    root._syncConfirm = syncConfirm; syncConfirm();
  }
  function renderCreateUnit() {
    const details = el('details', null, 'add-unit'); details.append(el('summary', 'Aggiungi appartamento'));
    const form = el('form', null, 'add-unit-form');
    const label = input('text', ''); label.maxLength = 120;
    const section = input('text', ''); section.required = false; section.maxLength = 80;
    form.append(createField('Appartamento', label), createField('Scala (facoltativa)', section));
    const submit = el('button', 'Aggiungi', 'button outlined'); submit.type = 'submit'; form.append(submit);
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (busy || !label.value.trim()) return;
      if (hasUnsaved()) { status('Salva le righe modificate prima di aggiungere un appartamento.', true); return; }
      busy = true; submit.disabled = true; status('Aggiunta dell’appartamento…');
      try {
        const response = await request('/rest/v1/water_units', { method: 'POST', headers: { 'Content-Type': 'application/json', Prefer: 'return=representation' },
          body: JSON.stringify({ project_id: contextSnapshot.projectId, building_id: contextSnapshot.buildingId, label: label.value.trim(), section: section.value.trim() }) });
        if (!Array.isArray(response) || response.length !== 1) throw new Error();
        units.push(response[0]); await loadDraft(draft);
      } catch { status('Impossibile aggiungere l’appartamento. Controlla che non esista già e riprova.', true); }
      finally { busy = false; submit.disabled = false; }
    });
    details.append(form); root.append(details);
  }
  function renderRow(row, editable) {
    const form = el('form', null, 'review-row');
    const head = el('div', null, 'review-row-heading'); head.append(el('h2', row.source_row || 'Riga importata'));
    const source = el('p', `Estratto: appartamento ${row.source_unit_label || 'non rilevato'} · periodo ${row.source_period_label || 'non rilevato'} · valore ${row.source_amount || 'non rilevato'}`, 'source-values');
    form.append(head, source);
    if (Array.isArray(row.source_warnings) && row.source_warnings.length) form.append(el('p', 'Questa riga contiene valori da verificare con particolare attenzione.', 'review-warning'));
    const fields = el('div', null, 'review-fields');
    const unit = select([{ value: '', label: 'Seleziona' }, ...units.map(u => ({ value: u.id, label: [u.section, u.label].filter(Boolean).join(' · ') }))], row.unit_id);
    const kind = select([{ value: '', label: 'Seleziona' }, { value: 'cold', label: 'Acqua fredda' }, { value: 'hot', label: 'Acqua calda' }], row.kind);
    const start = input('date', row.period_start); const end = input('date', row.period_end);
    const amount = input('text', row.amount == null ? '' : String(row.amount)); amount.inputMode = 'decimal';
    const entries = [['Appartamento', unit], ['Tipo di acqua', kind], ['Dal', start], ['Al', end], ['Consumo (m³)', amount]];
    for (const [label, field] of entries) { field.disabled = !editable; fields.append(createField(label, field)); }
    form.append(fields);
    const error = el('p', '', 'inline-error'); error.setAttribute('role', 'alert'); form.append(error);
    const controls = el('div', null, 'review-row-controls');
    const save = el('button', 'Salva riga', 'button outlined'); save.type = 'submit'; save.disabled = !editable;
    const verify = el('input'); verify.type = 'checkbox'; verify.disabled = !editable;
    const verifyLabel = createField('Ho controllato questa riga ', verify); verifyLabel.className = 'verify-row';
    const remove = makeButton('Escludi riga', async () => {
      if (busy || !globalThis.confirm('Escludere definitivamente questa riga dalla bozza?')) return;
      busy = true; remove.disabled = true; root._syncConfirm();
      try {
        const query = new URLSearchParams({ id: uuidFilter(row.id), import_id: uuidFilter(draft.id) });
        const result = await request(`/rest/v1/water_import_rows?${query}`, { method: 'DELETE', headers: { Prefer: 'return=representation' } });
        if (!Array.isArray(result) || result.length !== 1) throw new Error();
        dirty.delete(row.id); checked.delete(row.id); rows = rows.filter(item => item.id !== row.id); form.remove();
        status('Riga esclusa.');
      } catch { error.textContent = 'Impossibile escludere la riga. Riprova.'; }
      finally { busy = false; remove.disabled = false; root._syncConfirm(); }
    }, 'button text');
    remove.disabled = !editable;
    controls.append(save, verifyLabel, remove); form.append(controls);
    for (const [, field] of entries) field.addEventListener('input', () => { dirty.add(row.id); checked.delete(row.id); verify.checked = false; root._syncConfirm(); });
    verify.addEventListener('change', () => {
      if (dirty.has(row.id)) { verify.checked = false; error.textContent = 'Salva la riga prima di confermare il controllo.'; }
      else if (verify.checked) {
        const checkedRow = reviewRow({ unit_id: row.unit_id || '', kind: row.kind || '', period_start: row.period_start || '', period_end: row.period_end || '', amount: row.amount == null ? '' : String(row.amount) });
        if (checkedRow.error) { verify.checked = false; error.textContent = checkedRow.error; }
        else { checked.add(row.id); error.textContent = ''; }
      } else checked.delete(row.id);
      root._syncConfirm();
    });
    form.addEventListener('submit', async event => {
      event.preventDefault(); if (busy) return;
      const result = reviewRow({ unit_id: unit.value, kind: kind.value, period_start: start.value, period_end: end.value, amount: amount.value });
      if (result.error) { error.textContent = result.error; return; }
      busy = true; save.disabled = true; verify.disabled = true; remove.disabled = true;
      for (const [, field] of entries) field.disabled = true;
      root._syncConfirm(); error.textContent = '';
      try {
        const query = new URLSearchParams({ id: uuidFilter(row.id), import_id: uuidFilter(draft.id) });
        const updated = await request(`/rest/v1/water_import_rows?${query}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json', Prefer: 'return=representation' }, body: JSON.stringify(result.value) });
        if (!Array.isArray(updated) || updated.length !== 1) throw new Error();
        Object.assign(row, result.value); dirty.delete(row.id); checked.delete(row.id); verify.checked = false; status('Riga salvata. Ora conferma di averla controllata.');
      } catch { error.textContent = 'Impossibile salvare la riga. Controlla i dati e riprova.'; }
      finally {
        busy = false; save.disabled = false; verify.disabled = false; remove.disabled = false;
        for (const [, field] of entries) field.disabled = false;
        root._syncConfirm();
      }
    });
    return form;
  }
  return { open, hasUnsaved, isBusy: () => busy, leave, invalidate() { epoch++; dirty.clear(); checked.clear(); root.replaceChildren(); } };
}
