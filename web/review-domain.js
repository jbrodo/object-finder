export function reviewRow(form) {
  const amount = form.amount.trim();
  if (!form.unit_id) return { error: 'Scegli un appartamento.' };
  if (!['cold', 'hot'].includes(form.kind)) return { error: 'Scegli il tipo di acqua.' };
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.period_start) || !/^\d{4}-\d{2}-\d{2}$/.test(form.period_end)) return { error: 'Inserisci entrambe le date del periodo.' };
  const validDate = value => { const d = new Date(value + 'T00:00:00Z'); return !Number.isNaN(d.valueOf()) && d.toISOString().slice(0, 10) === value; };
  if (!validDate(form.period_start) || !validDate(form.period_end) || form.period_start > form.period_end) return { error: 'Controlla le date: la fine deve seguire l’inizio.' };
  if (!/^\d+(?:[.,]\d{1,3})?$/.test(amount) || Number(amount.replace(',', '.')) >= 1e9) return { error: 'Inserisci un consumo non negativo con al massimo tre decimali.' };
  return { value: { unit_id: form.unit_id, kind: form.kind, period_start: form.period_start, period_end: form.period_end, amount: amount.replace(',', '.') } };
}

export function canReview(grants, project, user, action) {
  return grants.some(g => g.project_id === project && g.user_id === user && g.feature === 'water' && g.permission === action);
}
