/// <reference types="chrome" />
import { listClips, sendText } from './lib/api';
import type { Clip, ClipboardPayload, Msg, Settings } from './lib/types';

/*
 * Offscreen document: the only extension context that can touch the clipboard without a
 * visible page, and a cheap place to run a short sync timer while Chrome is open.
 */

const FAST_MS = 4000;
const SLOW_MS = 60000;
const MAX_BACKOFF_MS = 5 * 60000;
// Clipboard watcher: how often to look for a new copy while the user is active.
const WATCH_MS = 1000;
const MAX_AUTO_TEXT = 1024 * 1024;

let settings: Settings | null = null;
let fast = true;
let timer: ReturnType<typeof setTimeout> | undefined;
let lastSeen = 0;
let failures = 0;
const seen = new Set<string>();
/** Text of recent clips from any device. Copies that match are never re-sent (no echo loops). */
const recentText = new Set<string>();
let lastClipboard: string | null = null; // null = baseline not taken yet
let watchTimer: ReturnType<typeof setTimeout> | undefined;
let sending = false;

function rememberText(t: string | null | undefined) {
  if (!t) return;
  recentText.add(t);
  if (recentText.size > 200) recentText.delete(recentText.values().next().value as string);
}

const area = document.getElementById('clip-area') as HTMLTextAreaElement;
const pasteTarget = document.getElementById('paste-target') as HTMLDivElement;

function schedule(ms: number) {
  clearTimeout(timer);
  if (settings) timer = setTimeout(poll, ms);
}

async function poll() {
  if (!settings) return;
  try {
    const first = lastSeen === 0;
    // Look back 2 s and de-dupe by id so clips created in the same second are never missed.
    const res = await listClips(settings, { since: first ? undefined : lastSeen - 2, limit: first ? 20 : 50 });
    const fresh: Clip[] = [];
    for (const c of res.clips) {
      rememberText(c.content);
      if (seen.has(c.id)) continue;
      seen.add(c.id);
      if (!first) fresh.push(c);
      lastSeen = Math.max(lastSeen, c.created_at);
    }
    if (first) lastSeen = Math.max(lastSeen, res.server_time);
    if (seen.size > 500) { const keep = [...seen].slice(-200); seen.clear(); keep.forEach((i) => seen.add(i)); }
    if (fresh.length) {
      await chrome.runtime.sendMessage({ target: 'background', type: 'new-clips', clips: fresh } satisfies Msg).catch(() => undefined);
      await chrome.runtime.sendMessage({ target: 'popup', type: 'clips-changed' } satisfies Msg).catch(() => undefined);
    }
    failures = 0;
    schedule(fast ? FAST_MS : SLOW_MS);
  } catch {
    failures++;
    schedule(Math.min(MAX_BACKOFF_MS, (fast ? FAST_MS : SLOW_MS) * 2 ** failures));
  }
}

function readClipboard(): ClipboardPayload {
  area.value = '';
  area.focus();
  document.execCommand('paste');
  const text = area.value;
  if (text) return { text };

  // No text — try an image by pasting into a contenteditable element.
  pasteTarget.innerHTML = '';
  pasteTarget.focus();
  document.execCommand('paste');
  const img = pasteTarget.querySelector('img');
  const src = img?.getAttribute('src') ?? '';
  pasteTarget.innerHTML = '';
  if (src.startsWith('data:image/')) return { imageDataUrl: src };
  return {};
}

function writeClipboard(text: string) {
  // Remember what we wrote so the watcher doesn't send it straight back.
  rememberText(text);
  lastClipboard = text;
  area.value = text;
  area.select();
  document.execCommand('copy');
  area.value = '';
}

function readClipboardText(): string {
  area.value = '';
  area.focus();
  document.execCommand('paste');
  const t = area.value;
  area.value = '';
  return t;
}

/** Auto-send: notice when the user copies new text on this computer and upload it. */
async function watchClipboard() {
  clearTimeout(watchTimer);
  if (!settings?.autoSend) return;
  try {
    const text = readClipboardText();
    if (lastClipboard === null) {
      lastClipboard = text; // first look: take a baseline, don't upload what was already there
    } else if (text && text !== lastClipboard && !sending) {
      const previous = lastClipboard;
      lastClipboard = text;
      if (!recentText.has(text) && text.trim() && text.length <= MAX_AUTO_TEXT) {
        sending = true;
        rememberText(text);
        try {
          await sendText(settings, text);
          await chrome.runtime.sendMessage({ target: 'popup', type: 'clips-changed' } satisfies Msg).catch(() => undefined);
        } catch {
          recentText.delete(text);
          lastClipboard = previous; // still differs next tick, so the upload is retried
        } finally {
          sending = false;
        }
      }
    }
  } catch { /* clipboard unavailable for a moment */ }
  // Watch closely while the user is active; when idle/locked nobody is copying.
  watchTimer = setTimeout(watchClipboard, fast ? WATCH_MS : 15000);
}

function applyConfig(next: Settings | null, nextFast: boolean) {
  const serverChanged = next?.serverUrl !== settings?.serverUrl || next?.token !== settings?.token;
  settings = next;
  fast = nextFast;
  if (serverChanged) { lastSeen = 0; seen.clear(); failures = 0; }
  // Sync paused (or not paired): no server checks, no clipboard watching.
  if (!settings || !settings.syncEnabled) { clearTimeout(timer); clearTimeout(watchTimer); return; }
  if (settings.autoSend) void watchClipboard(); else clearTimeout(watchTimer);
  schedule(serverChanged ? 0 : fast ? FAST_MS : SLOW_MS);
}

chrome.runtime.onMessage.addListener((msg: Msg, _sender, sendResponse) => {
  if (msg.target !== 'offscreen') return false;
  switch (msg.type) {
    case 'config':
      applyConfig(msg.settings, msg.fast);
      return false;
    case 'read-clipboard':
      sendResponse(readClipboard());
      return false;
    case 'write-clipboard':
      writeClipboard(msg.text);
      sendResponse(true);
      return false;
  }
  return false;
});

chrome.runtime.sendMessage({ target: 'background', type: 'get-config' } satisfies Msg)
  .then((r: { settings: Settings | null; fast: boolean } | undefined) => r && applyConfig(r.settings, r.fast))
  .catch(() => undefined);
