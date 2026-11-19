import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { deleteClip, forgetImage, listClips, sendImage, sendText, setPinned, fetchImage } from '../lib/api';
import { applyTheme, isConfigured, loadSettings, saveSettings } from '../lib/settings';
import type { Clip, Msg, Settings } from '../lib/types';
import { ClipCard } from './ClipCard';
import { SettingsView } from './SettingsView';
import { IconDownload, IconImage, IconRefresh, IconSearch, IconSend, IconSettings, IconX, Logo } from './icons';

type Filter = 'all' | 'text' | 'image' | 'pinned';
const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'text', label: 'Text' },
  { id: 'image', label: 'Images' },
  { id: 'pinned', label: 'Pinned' },
];
const CACHE_KEY = 'clipt.cache';

function Toast({ text }: { text: string | null }) {
  if (!text) return null;
  return (
    <div className="anim-pop pointer-events-none absolute bottom-4 left-1/2 z-30 -translate-x-1/2 rounded-full bg-ink px-4 py-2 text-[12.5px] font-medium text-canvas shadow-lg">
      {text}
    </div>
  );
}

function Lightbox({ url, clip, onClose }: { url: string; clip: Clip; onClose: () => void }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);
  const ext = (clip.mime?.split('/')[1] ?? 'png').replace('jpeg', 'jpg');
  return (
    <div className="anim-pop absolute inset-0 z-20 flex flex-col bg-black/92" onClick={onClose}>
      <div className="flex justify-end gap-1 p-2" onClick={(e) => e.stopPropagation()}>
        <a href={url} download={`clipt-${clip.id.slice(0, 8)}.${ext}`} aria-label="Download"
          className="grid size-9 place-items-center rounded-full text-white/80 hover:bg-white/10 hover:text-white">
          <IconDownload />
        </a>
        <button onClick={onClose} aria-label="Close" className="grid size-9 place-items-center rounded-full text-white/80 hover:bg-white/10 hover:text-white">
          <IconX />
        </button>
      </div>
      <div className="flex min-h-0 flex-1 items-center justify-center p-3">
        <img src={url} alt="" className="max-h-full max-w-full rounded-lg object-contain" onClick={(e) => e.stopPropagation()} />
      </div>
    </div>
  );
}

export function App() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [view, setView] = useState<'list' | 'settings'>('list');
  const [clips, setClips] = useState<Clip[]>([]);
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [lightbox, setLightbox] = useState<{ clip: Clip; url: string } | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout>>();
  const fileInput = useRef<HTMLInputElement>(null);

  const showToast = useCallback((t: string) => {
    setToast(t);
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 1800);
  }, []);

  // Boot: settings + last cached list for an instant first paint.
  useEffect(() => {
    chrome.action.setBadgeText({ text: '' });
    (async () => {
      const s = await loadSettings();
      applyTheme(s.theme);
      setSettings(s);
      if (!isConfigured(s)) { setView('settings'); return; }
      const cached = (await chrome.storage.local.get(CACHE_KEY))[CACHE_KEY] as Clip[] | undefined;
      if (cached) setClips(cached);
    })();
  }, []);

  const refresh = useCallback(async () => {
    if (!isConfigured(settings)) return;
    setLoading(true);
    try {
      const res = await listClips(settings, { limit: 100 });
      setClips(res.clips);
      setError(null);
      await chrome.storage.local.set({ [CACHE_KEY]: res.clips.slice(0, 50) });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [settings]);

  useEffect(() => { void refresh(); }, [refresh]);

  // Live updates pushed by the offscreen sync loop.
  useEffect(() => {
    const listener = (msg: Msg) => { if (msg.target === 'popup' && msg.type === 'clips-changed') void refresh(); };
    chrome.runtime.onMessage.addListener(listener);
    return () => chrome.runtime.onMessage.removeListener(listener);
  }, [refresh]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return clips.filter((c) => {
      if (filter === 'text' && c.type !== 'text') return false;
      if (filter === 'image' && c.type !== 'image') return false;
      if (filter === 'pinned' && !c.is_pinned) return false;
      if (q && !(c.content ?? '').toLowerCase().includes(q) && !c.device_name.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [clips, filter, query]);

  const upsert = (clip: Clip) => setClips((cs) => [clip, ...cs.filter((c) => c.id !== clip.id)].sort((a, b) => b.created_at - a.created_at));

  const sendBlob = async (blob: Blob, name: string) => {
    if (!settings) return;
    setSending(true);
    try {
      upsert(await sendImage(settings, blob, name));
      showToast('Image sent ✓');
    } catch (e) {
      showToast(`Failed: ${(e as Error).message}`);
    } finally {
      setSending(false);
    }
  };

  const sendDraft = async (text = draft) => {
    if (!settings || !text.trim()) return;
    setSending(true);
    try {
      upsert(await sendText(settings, text));
      setDraft('');
      showToast('Sent to Clipverse ✓');
    } catch (e) {
      showToast(`Failed: ${(e as Error).message}`);
    } finally {
      setSending(false);
    }
  };

  const sendCurrentClipboard = async () => {
    try {
      const items = await navigator.clipboard.read();
      for (const item of items) {
        const imgType = item.types.find((t) => t.startsWith('image/'));
        if (imgType) return void sendBlob(await item.getType(imgType), 'clipboard.png');
      }
      const text = await navigator.clipboard.readText();
      if (text.trim()) return void sendDraft(text);
      showToast('Clipboard is empty');
    } catch {
      showToast('Clipboard access was blocked');
    }
  };

  const onPaste = (e: React.ClipboardEvent) => {
    const file = [...e.clipboardData.files].find((f) => f.type.startsWith('image/'));
    if (file) { e.preventDefault(); void sendBlob(file, file.name || 'pasted.png'); }
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const files = [...e.dataTransfer.files].filter((f) => f.type.startsWith('image/'));
    files.forEach((f) => void sendBlob(f, f.name));
    if (!files.length) {
      const text = e.dataTransfer.getData('text/plain');
      if (text) void sendDraft(text);
    }
  };

  const copyClip = async (clip: Clip) => {
    if (!settings) return;
    if (clip.type === 'text') {
      await navigator.clipboard.writeText(clip.content ?? '');
    } else {
      // Clipboard only accepts PNG images; convert whatever we stored.
      const blob = await fetchImage(settings, clip.id);
      const png = blob.type === 'image/png' ? blob : await toPng(blob);
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]);
    }
  };

  const shareClip = async (clip: Clip) => {
    if (!settings) return;
    if (clip.type === 'image') {
      const blob = await fetchImage(settings, clip.id);
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `clipt-${clip.id.slice(0, 8)}.${(clip.mime?.split('/')[1] ?? 'png').replace('jpeg', 'jpg')}`;
      a.click();
      return;
    }
    const text = clip.content ?? '';
    if (navigator.share) {
      try { await navigator.share(/^https?:\/\/\S+$/.test(text) ? { url: text } : { text }); return; } catch { /* cancelled */ }
    }
    await navigator.clipboard.writeText(text);
    showToast('Copied — sharing not available here');
  };

  const togglePin = async (clip: Clip) => {
    if (!settings) return;
    upsert({ ...clip, is_pinned: !clip.is_pinned });
    try {
      upsert(await setPinned(settings, clip.id, !clip.is_pinned));
      showToast(clip.is_pinned ? 'Unpinned' : 'Pinned — kept forever');
    } catch (e) {
      upsert(clip);
      showToast(`Failed: ${(e as Error).message}`);
    }
  };

  const remove = async (clip: Clip) => {
    if (!settings) return;
    setClips((cs) => cs.filter((c) => c.id !== clip.id));
    try {
      await deleteClip(settings, clip.id);
      if (clip.type === 'image') await forgetImage(settings, clip.id);
    } catch (e) {
      upsert(clip);
      showToast(`Failed: ${(e as Error).message}`);
    }
  };

  if (!settings) return <div className="h-full bg-canvas" />;

  async function toggleSync() {
    const next = await saveSettings({ syncEnabled: !settings!.syncEnabled });
    setSettings(next);
    showToast(next.syncEnabled ? 'Automatic sync on' : 'Automatic sync paused');
    await chrome.runtime.sendMessage({ target: 'background', type: 'settings-changed' } satisfies Msg).catch(() => undefined);
  }

  if (view === 'settings') {
    return (
      <div className="relative h-full">
        <SettingsView
          settings={settings}
          onSaved={(s) => { setSettings(s); }}
          onBack={isConfigured(settings) ? () => setView('list') : undefined}
        />
      </div>
    );
  }

  return (
    <div
      className="relative flex h-full flex-col"
      onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
      onDragLeave={(e) => { if (e.currentTarget === e.target) setDragging(false); }}
      onDrop={onDrop}
    >
      <header className="flex h-14 shrink-0 items-center gap-2 px-4">
        <Logo />
        <span className="text-[17px] font-bold tracking-tight">clipverse</span>
        <span className={`ml-1 size-1.5 rounded-full ${error ? 'bg-accent' : 'bg-ok'}`} title={error ?? 'Connected'} />
        <span className="flex-1" />
        <button
          role="switch"
          aria-checked={settings.syncEnabled}
          onClick={toggleSync}
          title={settings.syncEnabled ? 'Automatic sync is on — click to pause' : 'Automatic sync is paused — click to resume'}
          className={`inline-flex h-7 items-center gap-1.5 rounded-full px-2.5 text-[11.5px] font-semibold transition ${
            settings.syncEnabled ? 'bg-accent-soft text-accent' : 'bg-chip text-ink-3'
          }`}
        >
          <span className={`relative h-3.5 w-6 rounded-full transition ${settings.syncEnabled ? 'bg-accent' : 'bg-line'}`}>
            <span className={`absolute top-0.5 size-2.5 rounded-full bg-white transition-all ${settings.syncEnabled ? 'left-3' : 'left-0.5'}`} />
          </span>
          {settings.syncEnabled ? 'Sync on' : 'Paused'}
        </button>
        <button onClick={() => void refresh()} aria-label="Refresh" className="grid size-8 place-items-center rounded-full text-ink-2 hover:bg-chip">
          <IconRefresh className={loading ? 'animate-spin' : ''} />
        </button>
        <button onClick={() => setView('settings')} aria-label="Settings" className="grid size-8 place-items-center rounded-full text-ink-2 hover:bg-chip">
          <IconSettings />
        </button>
      </header>

      {/* Composer: type, paste (text or screenshot), or drop. */}
      <div className="px-4">
        <div className={`rounded-2xl border bg-card p-2 transition ${dragging ? 'border-accent border-dashed bg-accent-soft' : 'border-line'}`}>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onPaste={onPaste}
            onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); void sendDraft(); } }}
            rows={2}
            placeholder={dragging ? 'Drop image to send' : 'Type, paste text or a screenshot, or drop an image…'}
            className="block w-full resize-none bg-transparent px-1.5 py-1 text-[13px] text-ink placeholder:text-ink-3 focus:outline-none"
          />
          <div className="flex items-center gap-1.5">
            <button
              onClick={() => fileInput.current?.click()}
              aria-label="Choose image"
              className="grid size-8 place-items-center rounded-full text-ink-2 hover:bg-chip"
            >
              <IconImage size={17} />
            </button>
            <input
              ref={fileInput}
              type="file"
              accept="image/*"
              multiple
              hidden
              onChange={(e) => { [...(e.target.files ?? [])].forEach((f) => void sendBlob(f, f.name)); e.target.value = ''; }}
            />
            <button
              onClick={sendCurrentClipboard}
              disabled={sending}
              className="h-8 rounded-full border border-line px-3 text-[12px] font-medium text-ink-2 hover:bg-chip hover:text-ink disabled:opacity-60"
            >
              Send clipboard
            </button>
            <span className="flex-1" />
            <button
              onClick={() => void sendDraft()}
              disabled={!draft.trim() || sending}
              aria-label="Send"
              className="inline-flex h-8 items-center gap-1 rounded-full bg-accent pl-3 pr-2.5 text-[12.5px] font-semibold text-white transition hover:brightness-110 disabled:opacity-40"
            >
              {sending ? 'Sending…' : 'Send'} <IconSend size={15} />
            </button>
          </div>
        </div>
      </div>

      <div className="mt-3 flex items-center gap-2 px-4">
        <div className="relative flex-1">
          <IconSearch size={15} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-ink-3" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search"
            className="h-8 w-full rounded-full border border-line bg-card pl-8 pr-3 text-[12.5px] text-ink placeholder:text-ink-3 focus:border-accent focus:outline-none"
          />
        </div>
      </div>
      <nav className="mt-2 flex gap-1.5 px-4" aria-label="Filter">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            onClick={() => setFilter(f.id)}
            aria-pressed={filter === f.id}
            className={`h-7 rounded-full px-3 text-[12px] font-medium transition ${
              filter === f.id ? 'bg-ink text-canvas' : 'bg-chip text-ink-2 hover:text-ink'
            }`}
          >
            {f.label}
          </button>
        ))}
      </nav>

      <main className="scroll-thin mt-3 flex-1 space-y-2.5 overflow-y-auto px-4 pb-4">
        {error && clips.length === 0 && (
          <div className="rounded-2xl border border-line bg-card p-4 text-center text-[12.5px] text-ink-2">
            Couldn’t reach your server.<br /><span className="text-ink-3">{error}</span>
          </div>
        )}
        {!error && visible.length === 0 && !loading && (
          <div className="flex flex-col items-center pt-10 text-center">
            <div className="opacity-90"><Logo size={36} /></div>
            <p className="mt-3 text-[13.5px] font-semibold">{clips.length ? 'Nothing matches' : 'No clips yet'}</p>
            <p className="mt-1 max-w-[240px] text-[12px] text-ink-3">
              {clips.length ? 'Try another filter or search.' : 'Share anything from your phone, or paste here to send it to your phone.'}
            </p>
          </div>
        )}
        {visible.map((clip) => (
          <ClipCard
            key={clip.id}
            clip={clip}
            settings={settings}
            onCopy={copyClip}
            onPin={togglePin}
            onDelete={remove}
            onShare={shareClip}
            onOpenImage={(c, url) => setLightbox({ clip: c, url })}
          />
        ))}
      </main>

      {lightbox && <Lightbox url={lightbox.url} clip={lightbox.clip} onClose={() => setLightbox(null)} />}
      <Toast text={toast} />
    </div>
  );
}

async function toPng(blob: Blob): Promise<Blob> {
  const bmp = await createImageBitmap(blob);
  const canvas = new OffscreenCanvas(bmp.width, bmp.height);
  canvas.getContext('2d')!.drawImage(bmp, 0, 0);
  return canvas.convertToBlob({ type: 'image/png' });
}
