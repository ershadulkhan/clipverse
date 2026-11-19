import type { Clip, Settings } from './types';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(s: Settings, path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`${s.serverUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${s.token}`, ...(init.headers || {}) },
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch { /* not JSON */ }
    if (res.status === 401) msg = 'Wrong sync token';
    throw new ApiError(res.status, msg);
  }
  return res.json() as Promise<T>;
}

export interface ListQuery { type?: 'text' | 'image'; pinned?: boolean; q?: string; since?: number; limit?: number }

export async function listClips(s: Settings, query: ListQuery = {}): Promise<{ clips: Clip[]; server_time: number }> {
  const p = new URLSearchParams();
  if (query.type) p.set('type', query.type);
  if (query.pinned) p.set('pinned', '1');
  if (query.q) p.set('q', query.q);
  if (query.since) p.set('since', String(query.since));
  p.set('limit', String(query.limit ?? 100));
  return request(s, `/v1/clips?${p}`);
}

export async function latestClip(s: Settings, excludeSelf = true): Promise<Clip | null> {
  const q = excludeSelf ? `?exclude_device=${encodeURIComponent(s.deviceId)}` : '';
  return (await request<{ clip: Clip | null }>(s, `/v1/clips/latest${q}`)).clip;
}

export async function sendText(s: Settings, content: string): Promise<Clip> {
  const body = { content, device_id: s.deviceId, device_name: s.deviceName, device_platform: s.platform };
  return (await request<{ clip: Clip }>(s, '/v1/clips', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })).clip;
}

export async function sendImage(s: Settings, blob: Blob, filename = 'image'): Promise<Clip> {
  const form = new FormData();
  form.append('file', blob, filename);
  form.append('device_id', s.deviceId);
  form.append('device_name', s.deviceName);
  form.append('device_platform', s.platform);
  return (await request<{ clip: Clip }>(s, '/v1/clips/image', { method: 'POST', body: form })).clip;
}

export async function setPinned(s: Settings, id: string, pinned: boolean): Promise<Clip> {
  return (await request<{ clip: Clip }>(s, `/v1/clips/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ is_pinned: pinned }),
  })).clip;
}

export async function deleteClip(s: Settings, id: string): Promise<void> {
  await request(s, `/v1/clips/${id}`, { method: 'DELETE' });
}

export async function createPairCode(s: Settings): Promise<{ code: string; expires_at: number }> {
  return request(s, '/v1/pair-codes', { method: 'POST' });
}

/** Exchanges a one-time code for the server's URL and sync token (no token needed yet). */
export async function redeemPairCode(serverUrl: string, code: string): Promise<{ server_url: string; token: string }> {
  const res = await fetch(`${serverUrl}/pair/redeem`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { msg = (await res.json()).error || msg; } catch { /* not JSON */ }
    throw new ApiError(res.status, msg);
  }
  return res.json();
}

export async function checkConfig(s: Settings): Promise<{ ok: boolean; retention_days: number }> {
  return request(s, '/v1/config');
}

export async function registerDevice(s: Settings): Promise<void> {
  await request(s, '/v1/devices', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: s.deviceId, name: s.deviceName, platform: s.platform }),
  });
}

/**
 * Image bytes, cached in the Cache Storage API so scrolling history never
 * re-downloads an image (clip images are immutable).
 */
export async function fetchImage(s: Settings, id: string): Promise<Blob> {
  const url = `${s.serverUrl}/v1/clips/${id}/image`;
  const cache = await caches.open('clipt-images-v1');
  const hit = await cache.match(url);
  if (hit) return hit.blob();
  // A just-uploaded image can take a few seconds to reach every Cloudflare location (KV), so retry briefly.
  let res: Response | null = null;
  for (let attempt = 0; attempt < 6; attempt++) {
    res = await fetch(url, { headers: { Authorization: `Bearer ${s.token}` } });
    if (res.ok || (res.status !== 503 && res.status !== 404)) break;
    await new Promise((r) => setTimeout(r, 2500));
  }
  if (!res || !res.ok) throw new ApiError(res?.status ?? 0, `Image HTTP ${res?.status}`);
  await cache.put(url, res.clone());
  return res.blob();
}

export async function forgetImage(s: Settings, id: string): Promise<void> {
  const cache = await caches.open('clipt-images-v1');
  await cache.delete(`${s.serverUrl}/v1/clips/${id}/image`);
}

export async function dataUrlToBlob(dataUrl: string): Promise<Blob> {
  return (await fetch(dataUrl)).blob();
}
