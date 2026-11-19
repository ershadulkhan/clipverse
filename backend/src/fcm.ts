import type { Env } from './index';

/**
 * Optional Firebase Cloud Messaging push.
 * When a desktop sends a clip, Android devices with an FCM token get one
 * high-priority *data* message. Nothing polls; the phone stays in Doze until this arrives.
 */

type ServiceAccount = { client_email: string; private_key: string; project_id?: string };

let cachedToken: { token: string; exp: number } | null = null;

function b64url(data: ArrayBuffer | Uint8Array | string): string {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : new Uint8Array(data);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function pemToDer(pem: string): ArrayBuffer {
  const b64 = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

async function accessToken(sa: ServiceAccount): Promise<string> {
  const t = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > t) return cachedToken.token;

  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: t,
    exp: t + 3600,
  }));
  const key = await crypto.subtle.importKey(
    'pkcs8', pemToDer(sa.private_key), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${claims}`));
  const jwt = `${header}.${claims}.${b64url(sig)}`;

  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }),
  });
  if (!res.ok) throw new Error(`FCM auth failed: ${res.status}`);
  const json = await res.json<{ access_token: string; expires_in: number }>();
  cachedToken = { token: json.access_token, exp: t + json.expires_in };
  return json.access_token;
}

export async function sendClipPush(
  env: Env,
  clip: { id: string; type: string; content: string; device_id: string | null; device_name: string; device_platform: string },
): Promise<void> {
  // Only desktop → phone needs a push; phones already know about their own clips.
  if (!env.FCM_SERVICE_ACCOUNT || !env.FCM_PROJECT_ID || clip.device_platform === 'android') return;
  try {
    const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT) as ServiceAccount;
    const { results } = await env.DB.prepare(
      "SELECT id, fcm_token FROM devices WHERE platform = 'android' AND fcm_token IS NOT NULL",
    ).all<{ id: string; fcm_token: string }>();
    if (!results.length) return;

    const token = await accessToken(sa);
    const preview = clip.type === 'text' ? clip.content.slice(0, 120) : '';
    // FCM data payloads max out at 4 KB; send short text whole so the phone can put it on the
    // clipboard instantly without a second request. Longer text is fetched by id.
    const full = clip.type === 'text' && new TextEncoder().encode(clip.content).length <= 3000 ? clip.content : '';
    await Promise.all(results.map(async (d) => {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: {
            token: d.fcm_token,
            android: { priority: 'high', ttl: '3600s' },
            data: { clip_id: clip.id, type: clip.type, device_name: clip.device_name, preview, content: full },
          },
        }),
      });
      // Token no longer valid (app uninstalled / data cleared): forget it.
      if (res.status === 404 || res.status === 400) {
        await env.DB.prepare('UPDATE devices SET fcm_token = NULL WHERE id = ?1').bind(d.id).run();
      }
    }));
  } catch (err) {
    console.error('FCM push failed', err);
  }
}
