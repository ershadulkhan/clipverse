import { useEffect, useState } from 'react';
import { fetchImage } from '../lib/api';
import { expiresIn, formatBytes, relativeTime } from '../lib/format';
import type { Clip, Settings } from '../lib/types';
import { IconCheck, IconCopy, IconLaptop, IconPhone, IconPin, IconShare, IconTrash } from './icons';

/** Session-level object-URL cache; bytes themselves live in Cache Storage. */
const objectUrls = new Map<string, string>();

export function useImageUrl(settings: Settings, clip: Clip): string | null {
  const [url, setUrl] = useState<string | null>(objectUrls.get(clip.id) ?? null);
  useEffect(() => {
    if (clip.type !== 'image' || objectUrls.has(clip.id)) return;
    let alive = true;
    fetchImage(settings, clip.id)
      .then((b) => {
        const u = URL.createObjectURL(b);
        objectUrls.set(clip.id, u);
        if (alive) setUrl(u);
      })
      .catch(() => undefined);
    return () => { alive = false; };
  }, [clip.id, clip.type, settings]);
  return url;
}

interface Props {
  clip: Clip;
  settings: Settings;
  onCopy: (clip: Clip) => Promise<void>;
  onPin: (clip: Clip) => void;
  onDelete: (clip: Clip) => void;
  onShare: (clip: Clip) => void;
  onOpenImage: (clip: Clip, url: string) => void;
}

export function ClipCard({ clip, settings, onCopy, onPin, onDelete, onShare, onOpenImage }: Props) {
  const [copied, setCopied] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const imageUrl = useImageUrl(settings, clip);
  const mine = clip.device_id === settings.deviceId;
  const long = (clip.content?.length ?? 0) > 220 || (clip.content?.split('\n').length ?? 0) > 5;

  useEffect(() => {
    if (!confirmDelete) return;
    const t = setTimeout(() => setConfirmDelete(false), 2500);
    return () => clearTimeout(t);
  }, [confirmDelete]);

  const copy = async () => {
    await onCopy(clip);
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  };

  return (
    <article className="anim-pop rounded-2xl border border-line bg-card px-3.5 pt-3 pb-2.5">
      <header className="flex items-center gap-2 text-[11.5px]">
        <span className="inline-flex min-w-0 items-center gap-1 rounded-full bg-chip px-2 py-0.5 font-medium text-ink-2">
          {clip.device_platform === 'android' ? <IconPhone size={12} /> : <IconLaptop size={12} />}
          <span className="max-w-[150px] truncate">{mine ? 'This device' : clip.device_name}</span>
        </span>
        <span className="whitespace-nowrap text-ink-3">{relativeTime(clip.created_at)}</span>
        <span className="flex-1" />
        <button
          onClick={() => onPin(clip)}
          aria-label={clip.is_pinned ? 'Unpin' : 'Pin'}
          title={clip.is_pinned ? 'Unpin' : 'Pin — keep forever'}
          className={`grid size-7 place-items-center rounded-full transition hover:bg-chip ${clip.is_pinned ? 'text-accent' : 'text-ink-3'}`}
        >
          <IconPin size={15} fill={clip.is_pinned ? 'currentColor' : 'none'} />
        </button>
        <button
          onClick={() => (confirmDelete ? onDelete(clip) : setConfirmDelete(true))}
          aria-label={confirmDelete ? 'Confirm delete' : 'Delete'}
          title={confirmDelete ? 'Click again to delete' : 'Delete'}
          className={`grid h-7 place-items-center rounded-full transition hover:bg-chip ${confirmDelete ? 'bg-accent-soft px-2 text-accent' : 'w-7 text-ink-3'}`}
        >
          {confirmDelete ? <span className="text-[11px] font-semibold">Delete?</span> : <IconTrash size={15} />}
        </button>
      </header>

      <div className="mt-2">
        {clip.type === 'text' ? (
          <>
            <p className={`select-text whitespace-pre-wrap break-words text-[13.5px] leading-[1.45] text-ink ${expanded ? '' : 'line-clamp-5'}`}>
              {clip.content}
            </p>
            {long && (
              <button onClick={() => setExpanded((v) => !v)} className="mt-1 text-[12px] font-medium text-ink-2 hover:text-ink">
                {expanded ? 'Show less' : 'Show more'}
              </button>
            )}
          </>
        ) : (
          <button
            onClick={() => imageUrl && onOpenImage(clip, imageUrl)}
            className="block w-full overflow-hidden rounded-xl border border-line bg-chip"
            aria-label="Open image"
          >
            {imageUrl ? (
              <img src={imageUrl} alt="" className="max-h-52 w-full object-cover" />
            ) : (
              <div className="h-32 w-full animate-pulse" />
            )}
          </button>
        )}
      </div>

      <footer className="mt-2.5 flex items-center gap-2">
        <button
          onClick={copy}
          className={`inline-flex h-8 items-center gap-1.5 rounded-full px-3.5 text-[12.5px] font-semibold transition ${
            copied ? 'bg-ok text-white' : 'bg-accent text-white hover:brightness-110'
          }`}
        >
          {copied ? <IconCheck size={15} /> : <IconCopy size={15} />}
          {copied ? 'Copied' : 'Copy'}
        </button>
        <button
          onClick={() => onShare(clip)}
          className="inline-flex h-8 items-center gap-1.5 rounded-full border border-line px-3 text-[12.5px] font-medium text-ink-2 transition hover:bg-chip hover:text-ink"
        >
          <IconShare size={15} />
          {clip.type === 'image' ? 'Save' : 'Share'}
        </button>
        <span className="flex-1" />
        <span className="whitespace-nowrap text-[11px] text-ink-3">
          {clip.type === 'image' && `${formatBytes(clip.size)} · `}
          {clip.is_pinned ? (
            <span className="rounded-full bg-accent-soft px-2 py-0.5 font-medium text-accent">Pinned</span>
          ) : (
            <span title="Unpinned clips are deleted automatically">{expiresIn(clip.expires_at)}</span>
          )}
        </span>
      </footer>
    </article>
  );
}
