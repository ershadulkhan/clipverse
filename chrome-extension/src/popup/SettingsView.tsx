import qrcode from 'qrcode-generator';
import { useEffect, useMemo, useState } from 'react';
import { checkConfig, createPairCode, redeemPairCode, registerDevice } from '../lib/api';
import {
  applyTheme, displayPairCode, isConfigured, normalizeUrl, pairingLink, parsePairCode, parsePairingLink, saveSettings,
} from '../lib/settings';
import type { Msg, Settings, Theme } from '../lib/types';
import { IconBack, IconBolt, IconCheck, IconCopy, IconRefresh } from './icons';

function QrCode({ text }: { text: string }) {
  const svg = useMemo(() => {
    const qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    return qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
  }, [text]);
  // White plate keeps the code scannable in dark and AMOLED themes.
  return <div className="size-44 rounded-2xl bg-white p-2" dangerouslySetInnerHTML={{ __html: svg }} />;
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[12px] font-medium text-ink-2">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[11px] text-ink-3">{hint}</span>}
    </label>
  );
}

function Toggle({ checked, onChange, label, desc }: { checked: boolean; onChange: (v: boolean) => void; label: string; desc: string }) {
  return (
    <button role="switch" aria-checked={checked} onClick={() => onChange(!checked)} className="flex w-full items-center gap-3 py-2 text-left">
      <span className="flex-1">
        <span className="block text-[13px] font-medium text-ink">{label}</span>
        <span className="block text-[11.5px] text-ink-3">{desc}</span>
      </span>
      <span className={`relative h-6 w-10 shrink-0 rounded-full transition ${checked ? 'bg-accent' : 'bg-line'}`}>
        <span className={`absolute top-0.5 size-5 rounded-full bg-white shadow transition-all ${checked ? 'left-[18px]' : 'left-0.5'}`} />
      </span>
    </button>
  );
}

const THEMES: { id: Theme; label: string }[] = [
  { id: 'system', label: 'System' },
  { id: 'light', label: 'Light' },
  { id: 'dark', label: 'Dark' },
  { id: 'amoled', label: 'AMOLED' },
];

const inputCls =
  'h-9 w-full rounded-xl border border-line bg-card px-3 text-[13px] text-ink placeholder:text-ink-3 focus:border-accent focus:outline-none';
const primaryBtn =
  'h-9 rounded-full bg-accent px-5 text-[13px] font-semibold text-white transition hover:brightness-110 disabled:opacity-50';

/** Shows a one-time code (valid 10 min) that another phone or computer can type to join. */
function PairCodeCard({ settings }: { settings: Settings }) {
  const [code, setCode] = useState<{ code: string; expires_at: number } | null>(null);
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<'code' | 'link' | null>(null);

  const generate = async () => {
    setError(null);
    try { setCode(await createPairCode(settings)); } catch (e) { setError((e as Error).message); }
  };

  useEffect(() => { void generate(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    const t = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 1000);
    return () => clearInterval(t);
  }, []);

  const left = code ? code.expires_at - now : 0;
  const shown = code && left > 0 ? displayPairCode(settings.serverUrl, code.code) : null;
  const copy = async (what: 'code' | 'link') => {
    await navigator.clipboard.writeText(what === 'code' ? shown ?? '' : pairingLink(settings));
    setCopied(what);
    setTimeout(() => setCopied(null), 1400);
  };

  return (
    <section className="rounded-2xl border border-line bg-card p-4">
      <h2 className="text-[13px] font-semibold">Pair another device</h2>
      <p className="mt-0.5 text-[11.5px] text-ink-3">Works on any network: Wi-Fi, mobile data, VPN.</p>

      <p className="mt-3 text-[11.5px] font-medium text-ink-2">Phone: scan in the Clipverse app</p>
      <div className="mt-2 flex justify-center"><QrCode text={pairingLink(settings)} /></div>

      <p className="mt-4 text-[11.5px] font-medium text-ink-2">Or type this code on the phone or another computer</p>
      <div className="mt-2 flex items-center gap-2">
        <button
          onClick={() => shown && copy('code')}
          className="flex-1 rounded-xl border border-dashed border-line bg-chip px-3 py-2 text-left font-mono text-[15px] font-semibold tracking-wide text-ink"
          title="Copy code"
        >
          {shown ?? (error ? '—' : 'Generating…')}
        </button>
        <button onClick={generate} aria-label="New code" title="New code" className="grid size-9 place-items-center rounded-full text-ink-2 hover:bg-chip">
          <IconRefresh size={16} />
        </button>
      </div>
      <p className="mt-1 text-[11px] text-ink-3">
        {error ? `Couldn’t create a code: ${error}` : shown
          ? `${copied === 'code' ? 'Copied ✓ · ' : ''}One use · expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`
          : code ? 'Expired — tap ↻ for a new code' : ''}
      </p>

      <button onClick={() => copy('link')} className="mt-3 flex items-center gap-1.5 text-[11.5px] font-medium text-ink-3 hover:text-ink">
        {copied === 'link' ? <IconCheck size={13} /> : <IconCopy size={13} />}
        {copied === 'link' ? 'Copied pairing link' : 'Copy permanent pairing link'}
      </button>
      <p className="mt-1 text-[10.5px] text-ink-3">The QR and link contain your sync token — don’t share them.</p>
    </section>
  );
}

export function SettingsView({ settings, onSaved, onBack }: { settings: Settings; onSaved: (s: Settings) => void; onBack?: () => void }) {
  const configured = isConfigured(settings);
  const [pairInput, setPairInput] = useState('');
  const [showManual, setShowManual] = useState(false);
  const [url, setUrl] = useState(settings.serverUrl);
  const [token, setToken] = useState(settings.token);
  const [name, setName] = useState(settings.deviceName);
  const [status, setStatus] = useState<{ kind: 'idle' | 'busy' | 'ok' | 'err'; text?: string }>({ kind: 'idle' });

  const connect = async (serverUrl: string, tok: string) => {
    const candidate: Settings = { ...settings, serverUrl: normalizeUrl(serverUrl), token: tok.trim(), deviceName: name.trim() || settings.deviceName };
    await checkConfig(candidate);
    const saved = await saveSettings(candidate);
    await registerDevice(saved).catch(() => undefined);
    await chrome.runtime.sendMessage({ target: 'background', type: 'settings-changed' } satisfies Msg).catch(() => undefined);
    setUrl(saved.serverUrl);
    setToken(saved.token);
    onSaved(saved);
  };

  const run = async (fn: () => Promise<void>) => {
    setStatus({ kind: 'busy' });
    try {
      await fn();
      setStatus({ kind: 'ok', text: 'Connected ✓' });
    } catch (e) {
      setStatus({ kind: 'err', text: (e as Error).message });
    }
  };

  /** Accepts a one-time code ("account-K7Q4MX") or a full clipverse://pair link. */
  const pairWithCode = () => run(async () => {
    const link = parsePairingLink(pairInput);
    if (link) return connect(link.serverUrl, link.token);
    const parsed = parsePairCode(pairInput);
    if (!parsed) throw new Error('That doesn’t look like a pairing code (e.g. yourname-K7Q4MX)');
    const res = await redeemPairCode(parsed.serverUrl, parsed.code);
    await connect(res.server_url, res.token);
  });

  const saveManual = () => run(async () => {
    const link = parsePairingLink(url);
    const u = link?.serverUrl ?? url;
    const t = link?.token ?? token;
    if (!u || !t) throw new Error('Server URL and sync token are both required.');
    await connect(u, t);
  });

  const setTheme = async (theme: Theme) => {
    applyTheme(theme);
    onSaved(await saveSettings({ theme }));
  };

  // Every switch applies immediately: tell the background to re-push config to the sync loop.
  const toggle = async (patch: Partial<Settings>) => {
    onSaved(await saveSettings(patch));
    await chrome.runtime.sendMessage({ target: 'background', type: 'settings-changed' } satisfies Msg).catch(() => undefined);
  };

  const statusLine = status.text && (
    <span className={`text-[12px] ${status.kind === 'err' ? 'text-accent' : 'text-ok'}`}>{status.text}</span>
  );

  return (
    <div className="flex h-full flex-col">
      <header className="flex h-14 shrink-0 items-center gap-2 border-b border-line px-3">
        {onBack && (
          <button onClick={onBack} aria-label="Back" className="grid size-8 place-items-center rounded-full text-ink-2 hover:bg-chip">
            <IconBack />
          </button>
        )}
        <h1 className="text-[15px] font-semibold">{configured ? 'Settings' : 'Set up Clipverse'}</h1>
      </header>

      <div className="scroll-thin flex-1 space-y-5 overflow-y-auto px-4 py-4">
        {!configured && (
          <section className="space-y-3">
            <p className="text-[12.5px] leading-relaxed text-ink-2">
              Already use Clipverse on another device? Open its Settings → <b>Pair another device</b> and type the code here.
            </p>
            <Field label="Pairing code" hint="Like yourname-K7Q4MX. A clipverse://pair link works too.">
              <input
                className={`${inputCls} font-mono`}
                value={pairInput}
                onChange={(e) => setPairInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && pairInput.trim() && void pairWithCode()}
                placeholder="yourname-K7Q4MX"
                spellCheck={false}
                autoFocus
              />
            </Field>
            <div className="flex items-center gap-3">
              <button onClick={pairWithCode} disabled={status.kind === 'busy' || !pairInput.trim()} className={primaryBtn}>
                {status.kind === 'busy' ? 'Pairing…' : 'Pair'}
              </button>
              {statusLine}
            </div>
            <button onClick={() => setShowManual((v) => !v)} className="text-[12px] font-medium text-ink-3 hover:text-ink">
              {showManual ? 'Hide server details' : 'First device? Enter your server details'}
            </button>
          </section>
        )}

        {!configured && showManual && (
          <section className="space-y-3">
            {!configured && (
              <p className="text-[12px] leading-relaxed text-ink-3">
                Deploy the Clipverse Worker to your Cloudflare account (free), then enter its URL and the
                <span className="font-mono"> CLIPT_TOKEN</span> secret you set.
              </p>
            )}
            <Field label="Server URL" hint="e.g. https://clipt.your-name.workers.dev">
              <input className={inputCls} value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…workers.dev" spellCheck={false} />
            </Field>
            <Field label="Sync token">
              <input className={inputCls} type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="Your CLIPT_TOKEN" spellCheck={false} />
            </Field>
            <Field label="Device name">
              <input className={inputCls} value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
            </Field>
            <div className="flex items-center gap-3">
              <button onClick={saveManual} disabled={status.kind === 'busy'} className={primaryBtn}>
                {status.kind === 'busy' ? 'Connecting…' : configured ? 'Save' : 'Connect'}
              </button>
              {configured && statusLine}
            </div>
          </section>
        )}

        {configured && (
          <>
            <PairCodeCard settings={settings} />

            <section className="rounded-2xl border border-line bg-card px-4 py-2">
              <Toggle checked={settings.syncEnabled}
                onChange={(v) => toggle({ syncEnabled: v })}
                label="Sync" desc="Master switch. Off pauses all automatic syncing on this computer; you can still send by hand." />
              <div className="h-px bg-line" />
              <Toggle checked={settings.autoSend} onChange={(v) => toggle({ autoSend: v })}
                label="Auto-send what I copy" desc="Text you copy on this computer goes to your phone automatically." />
              <div className="h-px bg-line" />
              <Toggle checked={settings.autoReceive} onChange={(v) => toggle({ autoReceive: v })}
                label="Auto-paste from phone" desc="Text you copy on your phone lands on this clipboard — just press ⌘V / Ctrl+V." />
              <div className="h-px bg-line" />
              <Toggle checked={settings.notify} onChange={(v) => toggle({ notify: v })}
                label="Notify on new clips" desc="Show a notification when your phone sends something." />
            </section>

            <section className="rounded-2xl border border-line bg-card p-4">
              <h2 className="text-[13px] font-semibold">Appearance</h2>
              <div className="mt-2 grid grid-cols-4 gap-1 rounded-full bg-chip p-1" role="radiogroup" aria-label="Theme">
                {THEMES.map((t) => (
                  <button key={t.id} role="radio" aria-checked={settings.theme === t.id} onClick={() => setTheme(t.id)}
                    className={`h-7 rounded-full text-[12px] font-medium transition ${settings.theme === t.id ? 'bg-card text-ink shadow-sm' : 'text-ink-3 hover:text-ink'}`}>
                    {t.label}
                  </button>
                ))}
              </div>
              <p className="mt-2 text-[11px] text-ink-3">AMOLED uses pure black, which saves battery on OLED screens.</p>
            </section>

            <section className="space-y-3 rounded-2xl border border-line bg-card p-4">
              <h2 className="text-[13px] font-semibold">Server & this device</h2>
            {!configured && (
              <p className="text-[12px] leading-relaxed text-ink-3">
                Deploy the Clipverse Worker to your Cloudflare account (free), then enter its URL and the
                <span className="font-mono"> CLIPT_TOKEN</span> secret you set.
              </p>
            )}
            <Field label="Server URL" hint="e.g. https://clipt.your-name.workers.dev">
              <input className={inputCls} value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…workers.dev" spellCheck={false} />
            </Field>
            <Field label="Sync token">
              <input className={inputCls} type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="Your CLIPT_TOKEN" spellCheck={false} />
            </Field>
            <Field label="Device name">
              <input className={inputCls} value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
            </Field>
            <div className="flex items-center gap-3">
              <button onClick={saveManual} disabled={status.kind === 'busy'} className={primaryBtn}>
                {status.kind === 'busy' ? 'Connecting…' : configured ? 'Save' : 'Connect'}
              </button>
              {configured && statusLine}
            </div>
          </section>

            <section className="rounded-2xl border border-line bg-card p-4 text-[12px] text-ink-2">
              <div className="flex items-center gap-2 font-medium text-ink">
                <IconBolt size={15} className="text-ok" /> Smart sync active
              </div>
              <p className="mt-1 text-ink-3">
                Checks every 4 s while you’re using this computer, once a minute when idle, and never when Chrome is closed.
              </p>
              <p className="mt-3 font-medium text-ink">Shortcuts</p>
              <p className="mt-1 text-ink-3">Send clipboard: <kbd className="font-mono">{settings.platform === 'mac' ? '⌘⇧Y' : 'Alt+Shift+C'}</kbd></p>
              <p className="text-ink-3">Copy latest from phone: <kbd className="font-mono">{settings.platform === 'mac' ? '⌘⇧L' : 'Alt+Shift+V'}</kbd></p>
              <button onClick={() => chrome.tabs.create({ url: 'chrome://extensions/shortcuts' })} className="mt-1 font-medium text-accent hover:underline">
                Change shortcuts
              </button>
            </section>
          </>
        )}
      </div>
    </div>
  );
}
