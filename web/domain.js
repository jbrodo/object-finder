export function canViewWater(features, grants, projectId, userId) {
  return features.some(f => f.project_id === projectId && f.feature === 'water' && f.enabled === true)
    && grants.some(g => g.project_id === projectId && g.user_id === userId && g.feature === 'water' && g.permission === 'view');
}

export function isPublicKey(key) {
  if (!key || key.startsWith('YOUR_') || key.startsWith('sb_secret_')) return false;
  const parts = key.split('.');
  if (parts.length === 3) {
    try {
      const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      if (JSON.parse(atob(payload)).role === 'service_role') return false;
    } catch { return false; }
  }
  return true;
}

export function formatDate(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || '');
  return match ? `${match[3]}/${match[2]}/${match[1]}` : '—';
}

export function formatAmount(value) {
  const numeric = Number(value);
  return value !== null && value !== undefined && value !== '' && Number.isFinite(numeric)
    ? new Intl.NumberFormat('it-IT', { maximumFractionDigits: 3 }).format(numeric) : '—';
}
