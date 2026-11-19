import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { sendClipPush } from './fcm';

export interface Env {
  DB: D1Database;
  /** Workers KV holds image bytes (free tier, no card needed; 25 MiB per value). */
  IMAGES: KVNamespace;
  CLIPT_TOKEN: string;
  RETENTION_DAYS?: string;
  MAX_UPLOAD_MB?: string;
  FCM_PROJECT_ID?: string;
  FCM_APP_ID?: string;
  FCM_API_KEY?: string;
  FCM_SENDER_ID?: string;
  FCM_SERVICE_ACCOUNT?: string;
}

type ClipRow = {
  id: string;
  type: 'text' | 'image';
  content: string;
  mime: string | null;
  size: number;
  device_id: string | null;
  device_name: string;
  device_platform: string;
  is_pinned: number;
  created_at: number;
  expires_at: number;
};

const PLATFORMS = new Set(['android', 'mac', 'windows', 'linux', 'chromeos', 'other']);
const IMAGE_MIMES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/heic', 'image/avif']);
const MAX_TEXT_BYTES = 1024 * 1024; // 1 MB of text per clip

const now = () => Math.floor(Date.now() / 1000);
// KV values max out at 25 MiB; keep a little headroom.
const maxUploadMb = (env: Env) => Math.min(Number(env.MAX_UPLOAD_MB || '24'), 24);
const retentionSeconds = (env: Env) => Number(env.RETENTION_DAYS || '30') * 86400;

/** Constant-time string comparison so the token can't be guessed byte-by-byte via timing. */
function safeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  let diff = ab.length ^ bb.length;
  const len = Math.max(ab.length, bb.length);
  for (let i = 0; i < len; i++) diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
  return diff === 0;
}

function serialize(row: ClipRow) {
  return {
    id: row.id,
    type: row.type,
    content: row.type === 'text' ? row.content : null,
    mime: row.mime,
    size: row.size,
    device_id: row.device_id,
    device_name: row.device_name,
    device_platform: row.device_platform,
    is_pinned: row.is_pinned === 1,
    created_at: row.created_at,
    expires_at: row.expires_at,
  };
}

function cleanPlatform(p: unknown): string {
  return typeof p === 'string' && PLATFORMS.has(p) ? p : 'other';
}

function cleanName(n: unknown): string {
  const s = typeof n === 'string' ? n.trim() : '';
  return (s || 'Unknown device').slice(0, 64);
}

const app = new Hono<{ Bindings: Env }>();

app.use('*', cors({
  origin: '*',
  allowMethods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'],
  allowHeaders: ['Authorization', 'Content-Type', 'X-Device-Id'],
  maxAge: 86400,
}));

/** Friendly page for whoever opens the server address in a browser (e.g. right after deploying). */
app.get('/', (c) => {
  const url = new URL(c.req.url).origin;
  const ready = Boolean(c.env.CLIPT_TOKEN);
  return c.html(`<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>Clipverse server</title><style>
:root{--bg:#f7f7f9;--card:#fff;--line:#e8e8ed;--ink:#111114;--ink2:#5c5c66;--accent:#eb0028;--ok:#12a150}
@media (prefers-color-scheme:dark){:root{--bg:#0e0e10;--card:#1a1a1d;--line:#2a2a2e;--ink:#f4f4f6;--ink2:#a8a8b2;--accent:#ff2e4c;--ok:#2fd27a}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
main{max-width:560px;margin:56px auto;padding:0 16px}.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:24px}
h1{font-size:22px;margin:0 0 4px}.ok{color:var(--ok);font-weight:600}.bad{color:var(--accent);font-weight:600}
code{display:block;background:var(--bg);border:1px solid var(--line);border-radius:12px;padding:10px 12px;font:14px ui-monospace,Menlo,monospace;word-break:break-all;margin:6px 0 2px}
ol{padding-left:20px}li{margin:8px 0}a{color:var(--accent)}p.muted{color:var(--ink2);font-size:13px}</style></head>
<body><main><div class="card"><h1>Clipverse server</h1>
${ready ? '<div class="ok">✓ Running and ready</div>' : '<div class="bad">Almost there: the CLIPT_TOKEN secret isn’t set yet.</div>'}
<ol><li>Install the Clipverse extension and app — see the <a href="https://github.com/ershadulkhan/clipverse/blob/main/docs/INSTALL.md">install guide</a>.</li>
<li>In the extension choose <b>First device? Enter your server details</b> and enter:<code>${url}</code>plus the sync token you chose when deploying.</li>
<li>Add your phone and other computers with <b>Settings → Pair another device</b>.</li></ol>
<p class="muted">Your clips are stored only in your own Cloudflare account. Nobody can read them without your sync token.</p>
</div></main></body></html>`);
});
app.get('/health', (c) => c.json({ ok: true, time: now() }));

// ---------- Pairing codes (any network: the code is redeemed against this server) ----------

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I confusion
const CODE_TTL = 10 * 60;
const MAX_FAILS = 10; // per IP per 15 minutes

function newCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('');
}

/**
 * Redeem a one-time pairing code → this server's URL and sync token. Public (no token yet),
 * so it is brute-force limited: 10 wrong codes per IP per 15 min, codes live 10 min, one use.
 */
app.post('/pair/redeem', async (c) => {
  if (!c.env.CLIPT_TOKEN) return c.json({ error: 'Server not configured' }, 500);
  const ip = c.req.header('CF-Connecting-IP') || 'unknown';
  const t = now();
  const fails = await c.env.DB.prepare('SELECT COUNT(*) AS n FROM pair_fails WHERE ip = ?1 AND at > ?2')
    .bind(ip, t - 900).first<{ n: number }>();
  if ((fails?.n ?? 0) >= MAX_FAILS) return c.json({ error: 'Too many attempts — wait 15 minutes' }, 429);

  const body = await c.req.json<{ code?: string }>().catch(() => null);
  const code = (body?.code || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(-6);
  // Delete-and-return makes the code single-use even under concurrent redeems.
  const row = code.length === 6
    ? await c.env.DB.prepare('DELETE FROM pair_codes WHERE code = ?1 AND expires_at > ?2 RETURNING code').bind(code, t).first()
    : null;
  if (!row) {
    await c.env.DB.prepare('INSERT INTO pair_fails (ip, at) VALUES (?1, ?2)').bind(ip, t).run();
    return c.json({ error: 'Code not found or expired' }, 404);
  }
  return c.json({ server_url: new URL(c.req.url).origin, token: c.env.CLIPT_TOKEN });
});

// Every route below requires the sync token.
app.use('/v1/*', async (c, next) => {
  if (!c.env.CLIPT_TOKEN) {
    return c.json({ error: 'Server not configured: run `wrangler secret put CLIPT_TOKEN`' }, 500);
  }
  const header = c.req.header('Authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!token || !safeEqual(token, c.env.CLIPT_TOKEN)) {
    return c.json({ error: 'Unauthorized' }, 401);
  }
  await next();
});

/** Lets a freshly paired device verify credentials and fetch optional push config. */
app.get('/v1/config', (c) => {
  const e = c.env;
  const fcm = e.FCM_PROJECT_ID && e.FCM_APP_ID && e.FCM_API_KEY && e.FCM_SENDER_ID && e.FCM_SERVICE_ACCOUNT
    ? { project_id: e.FCM_PROJECT_ID, app_id: e.FCM_APP_ID, api_key: e.FCM_API_KEY, sender_id: e.FCM_SENDER_ID }
    : null;
  return c.json({
    ok: true,
    retention_days: Number(e.RETENTION_DAYS || '30'),
    max_upload_mb: maxUploadMb(e),
    fcm,
  });
});

/** Creates a one-time pairing code that a new phone or computer can type in. */
app.post('/v1/pair-codes', async (c) => {
  const t = now();
  await c.env.DB.prepare('DELETE FROM pair_codes WHERE expires_at <= ?1').bind(t).run();
  const code = newCode();
  await c.env.DB.prepare('INSERT INTO pair_codes (code, created_at, expires_at) VALUES (?1, ?2, ?3)')
    .bind(code, t, t + CODE_TTL).run();
  return c.json({ code, expires_at: t + CODE_TTL });
});

// ---------- Devices ----------

app.post('/v1/devices', async (c) => {
  const body = await c.req.json<{ id?: string; name?: string; platform?: string; fcm_token?: string | null }>().catch(() => null);
  if (!body?.id || typeof body.id !== 'string' || body.id.length > 64) return c.json({ error: 'id required' }, 400);
  // fcm_token: omitted/null = keep current, "" = remove (push disabled), string = set.
  const raw = body.fcm_token;
  const clear = raw === '' ? 1 : 0;
  const fcm = typeof raw === 'string' && raw.length > 0 && raw.length < 4096 ? raw : null;
  await c.env.DB.prepare(
    `INSERT INTO devices (id, name, platform, fcm_token, last_seen) VALUES (?1, ?2, ?3, ?4, ?5)
     ON CONFLICT(id) DO UPDATE SET name = ?2, platform = ?3,
       fcm_token = CASE WHEN ?6 = 1 THEN NULL ELSE COALESCE(?4, devices.fcm_token) END, last_seen = ?5`,
  ).bind(body.id, cleanName(body.name), cleanPlatform(body.platform), fcm, now(), clear).run();
  return c.json({ ok: true });
});

app.get('/v1/devices', async (c) => {
  const { results } = await c.env.DB.prepare(
    'SELECT id, name, platform, last_seen, fcm_token IS NOT NULL AS push FROM devices ORDER BY last_seen DESC',
  ).all();
  return c.json({ devices: results });
});

app.delete('/v1/devices/:id', async (c) => {
  await c.env.DB.prepare('DELETE FROM devices WHERE id = ?1').bind(c.req.param('id')).run();
  return c.json({ ok: true });
});

// ---------- Clips ----------

app.get('/v1/clips', async (c) => {
  const q = c.req.query();
  const where: string[] = ['(is_pinned = 1 OR expires_at > ?1)'];
  const args: (string | number)[] = [now()];
  if (q.type === 'text' || q.type === 'image') { where.push(`type = ?${args.length + 1}`); args.push(q.type); }
  if (q.pinned === '1') where.push('is_pinned = 1');
  if (q.since && /^\d+$/.test(q.since)) { where.push(`created_at > ?${args.length + 1}`); args.push(Number(q.since)); }
  if (q.before && /^\d+$/.test(q.before)) { where.push(`created_at < ?${args.length + 1}`); args.push(Number(q.before)); }
  if (q.q) {
    where.push(`(type = 'text' AND content LIKE ?${args.length + 1} ESCAPE '\\')`);
    args.push(`%${q.q.replace(/[\\%_]/g, (m) => '\\' + m)}%`);
  }
  const limit = Math.min(Math.max(Number(q.limit) || 50, 1), 200);
  const { results } = await c.env.DB.prepare(
    `SELECT * FROM clips WHERE ${where.join(' AND ')} ORDER BY created_at DESC, id DESC LIMIT ${limit}`,
  ).bind(...args).all<ClipRow>();
  return c.json({ clips: results.map(serialize), server_time: now() });
});

app.get('/v1/clips/latest', async (c) => {
  const exclude = c.req.query('exclude_device');
  const stmt = exclude
    ? c.env.DB.prepare('SELECT * FROM clips WHERE (device_id IS NULL OR device_id != ?1) AND (is_pinned = 1 OR expires_at > ?2) ORDER BY created_at DESC LIMIT 1').bind(exclude, now())
    : c.env.DB.prepare('SELECT * FROM clips WHERE is_pinned = 1 OR expires_at > ?1 ORDER BY created_at DESC LIMIT 1').bind(now());
  const row = await stmt.first<ClipRow>();
  if (!row) return c.json({ clip: null });
  return c.json({ clip: serialize(row) });
});

app.post('/v1/clips', async (c) => {
  const body = await c.req.json<{ content?: string; device_id?: string; device_name?: string; device_platform?: string }>().catch(() => null);
  const content = body?.content;
  if (typeof content !== 'string' || content.length === 0) return c.json({ error: 'content required' }, 400);
  const size = new TextEncoder().encode(content).length;
  if (size > MAX_TEXT_BYTES) return c.json({ error: 'Text too large (1 MB max)' }, 413);

  const t = now();
  // Echo / double-send guard: the same text within 60 s is the same clip (e.g. a device
  // syncing back something it just received). Return the existing one instead of a duplicate.
  const dup = await c.env.DB.prepare(
    "SELECT * FROM clips WHERE type = 'text' AND content = ?1 AND created_at > ?2 ORDER BY created_at DESC LIMIT 1",
  ).bind(content, t - 60).first<ClipRow>();
  if (dup) return c.json({ clip: serialize(dup), duplicate: true }, 200);

  const row: ClipRow = {
    id: crypto.randomUUID(),
    type: 'text',
    content,
    mime: 'text/plain',
    size,
    device_id: typeof body?.device_id === 'string' ? body.device_id.slice(0, 64) : null,
    device_name: cleanName(body?.device_name),
    device_platform: cleanPlatform(body?.device_platform),
    is_pinned: 0,
    created_at: t,
    expires_at: t + retentionSeconds(c.env),
  };
  await insertClip(c.env, row);
  c.executionCtx.waitUntil(sendClipPush(c.env, row));
  return c.json({ clip: serialize(row) }, 201);
});

app.post('/v1/clips/image', async (c) => {
  const maxBytes = maxUploadMb(c.env) * 1024 * 1024;
  const len = Number(c.req.header('Content-Length') || '0');
  if (len > maxBytes + 64 * 1024) return c.json({ error: `Image too large (${maxUploadMb(c.env)} MB max)` }, 413);

  const form = await c.req.formData().catch(() => null);
  const entry = form?.get('file') as unknown;
  if (!form || !entry || typeof entry === 'string') return c.json({ error: 'file required' }, 400);
  const file = entry as File;
  const mime = (file.type || '').toLowerCase();
  if (!IMAGE_MIMES.has(mime)) return c.json({ error: `Unsupported image type: ${mime || 'unknown'}` }, 415);
  if (file.size > maxBytes) return c.json({ error: 'Image too large' }, 413);

  const t = now();
  const id = crypto.randomUUID();
  const key = `clips/${id}`;
  await c.env.IMAGES.put(key, await file.arrayBuffer(), { metadata: { mime } });

  const deviceId = form.get('device_id');
  const row: ClipRow = {
    id,
    type: 'image',
    content: key,
    mime,
    size: file.size,
    device_id: typeof deviceId === 'string' ? deviceId.slice(0, 64) : null,
    device_name: cleanName(form.get('device_name')),
    device_platform: cleanPlatform(form.get('device_platform')),
    is_pinned: 0,
    created_at: t,
    expires_at: t + retentionSeconds(c.env),
  };
  try {
    await insertClip(c.env, row);
  } catch (err) {
    await c.env.IMAGES.delete(key);
    throw err;
  }
  c.executionCtx.waitUntil(sendClipPush(c.env, row));
  return c.json({ clip: serialize(row) }, 201);
});

app.get('/v1/clips/:id', async (c) => {
  const row = await c.env.DB.prepare('SELECT * FROM clips WHERE id = ?1').bind(c.req.param('id')).first<ClipRow>();
  if (!row) return c.json({ error: 'Not found' }, 404);
  return c.json({ clip: serialize(row) });
});

app.get('/v1/clips/:id/image', async (c) => {
  const row = await c.env.DB.prepare("SELECT content, mime FROM clips WHERE id = ?1 AND type = 'image'")
    .bind(c.req.param('id')).first<{ content: string; mime: string }>();
  if (!row) return c.json({ error: 'Not found' }, 404);
  // KV is eventually consistent across Cloudflare locations: a brand-new image can take a few
  // seconds to show up far from where it was written. Clients retry on 404 / 503.
  const body = await c.env.IMAGES.get(row.content, { type: 'stream' });
  if (!body) return c.json({ error: 'Image not available yet' }, 503, { 'Retry-After': '3' });
  return new Response(body, {
    headers: {
      'Content-Type': row.mime || 'application/octet-stream',
      // Clip images are immutable: let devices cache them forever.
      'Cache-Control': 'private, max-age=31536000, immutable',
      ETag: `"${c.req.param('id')}"`,
    },
  });
});

app.patch('/v1/clips/:id', async (c) => {
  const body = await c.req.json<{ is_pinned?: boolean }>().catch(() => null);
  if (typeof body?.is_pinned !== 'boolean') return c.json({ error: 'is_pinned (boolean) required' }, 400);
  const id = c.req.param('id');
  // Unpinning restarts the retention window so an old clip isn't deleted the moment it's unpinned.
  const res = body.is_pinned
    ? await c.env.DB.prepare('UPDATE clips SET is_pinned = 1 WHERE id = ?1').bind(id).run()
    : await c.env.DB.prepare('UPDATE clips SET is_pinned = 0, expires_at = ?2 WHERE id = ?1').bind(id, now() + retentionSeconds(c.env)).run();
  if (!res.meta.changes) return c.json({ error: 'Not found' }, 404);
  const row = await c.env.DB.prepare('SELECT * FROM clips WHERE id = ?1').bind(id).first<ClipRow>();
  return c.json({ clip: row ? serialize(row) : null });
});

app.delete('/v1/clips/:id', async (c) => {
  const id = c.req.param('id');
  const row = await c.env.DB.prepare('SELECT type, content FROM clips WHERE id = ?1').bind(id).first<{ type: string; content: string }>();
  if (!row) return c.json({ error: 'Not found' }, 404);
  if (row.type === 'image') await c.env.IMAGES.delete(row.content);
  await c.env.DB.prepare('DELETE FROM clips WHERE id = ?1').bind(id).run();
  return c.json({ ok: true });
});

app.onError((err, c) => {
  console.error(err);
  return c.json({ error: 'Internal error' }, 500);
});

app.notFound((c) => c.json({ error: 'Not found' }, 404));

async function insertClip(env: Env, r: ClipRow) {
  await env.DB.prepare(
    `INSERT INTO clips (id, type, content, mime, size, device_id, device_name, device_platform, is_pinned, created_at, expires_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)`,
  ).bind(r.id, r.type, r.content, r.mime, r.size, r.device_id, r.device_name, r.device_platform, r.is_pinned, r.created_at, r.expires_at).run();
}

/** Deletes expired, unpinned clips and their stored images. Runs on Cloudflare's cron, never on devices. */
export async function cleanupExpired(env: Env): Promise<number> {
  let total = 0;
  await env.DB.batch([
    env.DB.prepare('DELETE FROM pair_codes WHERE expires_at <= ?1').bind(now()),
    env.DB.prepare('DELETE FROM pair_fails WHERE at <= ?1').bind(now() - 3600),
  ]);
  for (;;) {
    const { results } = await env.DB.prepare(
      'SELECT id, type, content FROM clips WHERE is_pinned = 0 AND expires_at <= ?1 LIMIT 500',
    ).bind(now()).all<{ id: string; type: string; content: string }>();
    if (!results.length) break;
    const keys = results.filter((r) => r.type === 'image').map((r) => r.content);
    for (const k of keys) await env.IMAGES.delete(k);
    const ids = results.map((r) => r.id);
    await env.DB.prepare(`DELETE FROM clips WHERE id IN (${ids.map((_, i) => `?${i + 1}`).join(',')})`).bind(...ids).run();
    total += ids.length;
    if (results.length < 500) break;
  }
  return total;
}

export default {
  fetch: app.fetch,
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(cleanupExpired(env).then((n) => console.log(`clipt cleanup: removed ${n} expired clips`)));
  },
} satisfies ExportedHandler<Env>;
