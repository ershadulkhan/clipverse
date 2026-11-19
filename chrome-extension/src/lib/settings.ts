import type { Platform, Settings, Theme } from './types';

const KEY = 'clipt.settings';

export function detectPlatform(): Platform {
  const nav = navigator as Navigator & { userAgentData?: { platform?: string } };
  const p = (nav.userAgentData?.platform || navigator.platform || navigator.userAgent).toLowerCase();
  if (p.includes('mac')) return 'mac';
  if (p.includes('win')) return 'windows';
  if (p.includes('cros') || p.includes('chrome os')) return 'chromeos';
  if (p.includes('linux')) return 'linux';
  return 'other';
}

export function detectBrowser(): string {
  const nav = navigator as Navigator & { userAgentData?: { brands?: { brand: string }[] }; brave?: unknown };
  if (nav.brave) return 'Brave';
  const brands = nav.userAgentData?.brands?.map((b) => b.brand) ?? [];
  const ua = navigator.userAgent;
  if (brands.includes('Microsoft Edge') || ua.includes('Edg/')) return 'Edge';
  if (brands.includes('Opera') || ua.includes('OPR/')) return 'Opera';
  if (brands.includes('Vivaldi') || ua.includes('Vivaldi')) return 'Vivaldi';
  if (ua.includes('Arc/')) return 'Arc';
  return 'Chrome';
}

const PLATFORM_LABEL: Record<Platform, string> = {
  mac: 'macOS', windows: 'Windows', linux: 'Linux', chromeos: 'ChromeOS', android: 'Android', other: 'Desktop',
};

export const platformLabel = (p: Platform) => PLATFORM_LABEL[p] ?? 'Desktop';

function defaults(): Settings {
  const platform = detectPlatform();
  return {
    serverUrl: '',
    token: '',
    deviceId: crypto.randomUUID(),
    deviceName: `${platformLabel(platform)} • ${detectBrowser()}`,
    platform,
    autoReceive: true,
    autoSend: true,
    notify: true,
    theme: 'system',
    syncEnabled: true,
  };
}

export async function loadSettings(): Promise<Settings> {
  const stored = (await chrome.storage.local.get(KEY))[KEY] as Partial<Settings> | undefined;
  const merged = { ...defaults(), ...stored };
  if (!stored?.deviceId) await chrome.storage.local.set({ [KEY]: merged });
  return merged;
}

export async function saveSettings(patch: Partial<Settings>): Promise<Settings> {
  const next = { ...(await loadSettings()), ...patch };
  next.serverUrl = normalizeUrl(next.serverUrl);
  await chrome.storage.local.set({ [KEY]: next });
  return next;
}

export function normalizeUrl(u: string): string {
  let s = u.trim();
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) s = `https://${s}`;
  return s.replace(/\/+$/, '');
}

export const isConfigured = (s: Settings | null | undefined): s is Settings => !!s?.serverUrl && !!s?.token;

/** Pairing payload encoded into the QR code that the Android app scans. */
export function pairingLink(s: Settings): string {
  return `clipverse://pair?u=${encodeURIComponent(s.serverUrl)}&t=${encodeURIComponent(s.token)}`;
}

/** Default deployments live at https://clipt.<account>.workers.dev; the code shows just <account>. */
function codeLabel(serverUrl: string): string {
  const host = new URL(serverUrl).host;
  return host.match(/^clipt\.([^.]+)\.workers\.dev$/)?.[1] ?? host;
}

/** e.g. "yourname-K7Q4MX" — easy to read out or type on any device, on any network. */
export function displayPairCode(serverUrl: string, code: string): string {
  return `${codeLabel(serverUrl)}-${code}`;
}

/** Parses "<account or host>-<6 chars>" back into the server URL and the one-time code. */
export function parsePairCode(input: string): { serverUrl: string; code: string } | null {
  const t = input.trim();
  const i = t.lastIndexOf('-');
  if (i < 1) return null;
  const label = t.slice(0, i).trim().toLowerCase();
  const code = t.slice(i + 1).replace(/[^A-Za-z0-9]/g, '').toUpperCase();
  if (code.length !== 6 || !/^[a-z0-9.:-]+$/.test(label)) return null;
  const host = label.includes('.') || label.includes(':') ? label : `clipt.${label}.workers.dev`;
  const proto = /^(localhost|127\.)/.test(host) ? 'http' : 'https';
  return { serverUrl: `${proto}://${host}`, code };
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  try { localStorage.setItem('clipverse.theme', theme); } catch { /* storage unavailable */ }
}

export function parsePairingLink(link: string): { serverUrl: string; token: string } | null {
  try {
    const url = new URL(link.trim());
    if (url.protocol !== 'clipt:' && url.protocol !== 'clipverse:') return null;
    const u = url.searchParams.get('u');
    const t = url.searchParams.get('t');
    return u && t ? { serverUrl: normalizeUrl(u), token: t } : null;
  } catch {
    return null;
  }
}
