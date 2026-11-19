export function relativeTime(unixSeconds: number, nowMs = Date.now()): string {
  const diff = Math.max(0, Math.floor(nowMs / 1000) - unixSeconds);
  if (diff < 45) return 'Just now';
  if (diff < 3600) return `${Math.round(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.round(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.round(diff / 86400)}d ago`;
  return new Date(unixSeconds * 1000).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function expiresIn(expiresAt: number, nowMs = Date.now()): string {
  const left = expiresAt - Math.floor(nowMs / 1000);
  if (left <= 0) return 'Expiring';
  const days = Math.ceil(left / 86400);
  if (days > 1) return `Auto-deletes in ${days}d`;
  return `Auto-deletes in ${Math.max(1, Math.ceil(left / 3600))}h`;
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
