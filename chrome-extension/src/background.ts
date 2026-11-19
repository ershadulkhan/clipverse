/// <reference types="chrome" />
import { latestClip, registerDevice, sendImage, sendText, dataUrlToBlob } from './lib/api';
import { isConfigured, loadSettings, parsePairingLink, saveSettings } from './lib/settings';
import type { Clip, ClipboardPayload, Msg, Settings } from './lib/types';

/*
 * MV3 service worker. Desktop machines are plugged in, so "smart sync" is allowed here:
 * an offscreen document polls every ~4 s while the user is active and backs off to 60 s
 * when idle/locked. Alarms (min 30 s period) only keep the offscreen document alive.
 */

const OFFSCREEN_URL = 'offscreen.html';
let creating: Promise<void> | null = null;
let userActive = true;
const pendingNotifications = new Map<string, Clip>();

async function ensureOffscreen(): Promise<void> {
  const contexts = await chrome.runtime.getContexts({
    contextTypes: [chrome.runtime.ContextType.OFFSCREEN_DOCUMENT],
    documentUrls: [chrome.runtime.getURL(OFFSCREEN_URL)],
  });
  if (contexts.length) return;
  if (!creating) {
    creating = chrome.offscreen.createDocument({
      url: OFFSCREEN_URL,
      reasons: [chrome.offscreen.Reason.CLIPBOARD],
      justification: 'Read/write the clipboard for keyboard shortcuts and keep clips in sync while Chrome is open.',
    }).finally(() => { creating = null; });
  }
  await creating;
}

async function pushConfig(): Promise<void> {
  const settings = await loadSettings();
  // Toolbar badge shows when automatic sync is paused.
  if (isConfigured(settings) && !settings.syncEnabled) {
    await chrome.action.setBadgeBackgroundColor({ color: '#8a8a93' });
    await chrome.action.setBadgeText({ text: 'off' });
  } else if ((await chrome.action.getBadgeText({})) === 'off') {
    await chrome.action.setBadgeText({ text: '' });
  }
  await ensureOffscreen();
  const msg: Msg = { target: 'offscreen', type: 'config', settings: isConfigured(settings) ? settings : null, fast: userActive };
  await chrome.runtime.sendMessage(msg).catch(() => undefined);
}

async function toOffscreen<T>(msg: Msg): Promise<T> {
  await ensureOffscreen();
  return chrome.runtime.sendMessage(msg) as Promise<T>;
}

function notify(id: string, title: string, message: string, buttons?: { title: string }[]) {
  chrome.notifications.create(id, {
    type: 'basic',
    iconUrl: chrome.runtime.getURL('icons/icon128.png'),
    title,
    message: message || ' ',
    buttons,
    priority: 0,
  });
}

async function requireSettings(): Promise<Settings | null> {
  const s = await loadSettings();
  if (isConfigured(s)) return s;
  notify(`setup-${Date.now()}`, 'Clipverse is not set up', 'Open the Clipverse popup and add your server URL and sync token.');
  return null;
}

// ---------- Actions ----------

async function sendClipboard(): Promise<void> {
  const s = await requireSettings();
  if (!s) return;
  const payload = await toOffscreen<ClipboardPayload>({ target: 'offscreen', type: 'read-clipboard' });
  try {
    if (payload?.imageDataUrl) {
      await sendImage(s, await dataUrlToBlob(payload.imageDataUrl), 'clipboard.png');
      notify(`sent-${Date.now()}`, 'Sent to Clipverse ✓', 'Image from your clipboard');
    } else if (payload?.text?.trim()) {
      await sendText(s, payload.text);
      notify(`sent-${Date.now()}`, 'Sent to Clipverse ✓', payload.text.slice(0, 120));
    } else {
      notify(`empty-${Date.now()}`, 'Clipboard is empty', 'Copy some text or an image first.');
    }
  } catch (e) {
    notify(`err-${Date.now()}`, 'Could not send to Clipverse', (e as Error).message);
  }
}

async function copyClipText(clip: Clip): Promise<boolean> {
  if (clip.type !== 'text' || !clip.content) return false;
  await toOffscreen({ target: 'offscreen', type: 'write-clipboard', text: clip.content });
  return true;
}

async function pullLatest(): Promise<void> {
  const s = await requireSettings();
  if (!s) return;
  try {
    const clip = await latestClip(s, true);
    if (!clip) return notify(`none-${Date.now()}`, 'Nothing to paste yet', 'Send something from your phone first.');
    if (await copyClipText(clip)) {
      notify(`pulled-${Date.now()}`, `Copied from ${clip.device_name} ✓`, clip.content!.slice(0, 120));
    } else {
      pendingNotifications.set('open-popup', clip);
      notify('open-popup', `Image from ${clip.device_name}`, 'Open Clipverse to copy or download the image.');
    }
  } catch (e) {
    notify(`err-${Date.now()}`, 'Could not reach Clipverse', (e as Error).message);
  }
}

async function onNewClips(clips: Clip[]): Promise<void> {
  const s = await loadSettings();
  const incoming = clips.filter((c) => c.device_id !== s.deviceId);
  if (!incoming.length) return;

  const current = await chrome.action.getBadgeText({});
  const count = (Number(current) || 0) + incoming.length;
  await chrome.action.setBadgeBackgroundColor({ color: '#EB0028' });
  await chrome.action.setBadgeText({ text: count > 99 ? '99+' : String(count) });

  const newest = incoming[0];
  let copied = false;
  if (s.autoReceive) copied = await copyClipText(newest).catch(() => false);
  if (!s.notify) return;

  const id = `clip-${newest.id}`;
  pendingNotifications.set(id, newest);
  const body = newest.type === 'text' ? (newest.content ?? '').slice(0, 140) : 'Image';
  notify(
    id,
    copied ? `Copied from ${newest.device_name} ✓` : `New clip from ${newest.device_name}`,
    body,
    !copied && newest.type === 'text' ? [{ title: 'Copy' }] : undefined,
  );
}

// ---------- Context menus ----------

function setupMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: 'send-selection', title: 'Send “%s” to Clipverse', contexts: ['selection'] });
    chrome.contextMenus.create({ id: 'send-image', title: 'Send image to Clipverse', contexts: ['image'] });
    chrome.contextMenus.create({ id: 'send-link', title: 'Send link to Clipverse', contexts: ['link'] });
    chrome.contextMenus.create({ id: 'send-page', title: 'Send page URL to Clipverse', contexts: ['page'] });
  });
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  // Image downloads from arbitrary sites need host access; ask only when the user actually uses it.
  if (info.menuItemId === 'send-image' && info.srcUrl && !info.srcUrl.startsWith('data:')) {
    // Call request() first, with no await before it, so the click still counts as a user gesture.
    await chrome.permissions.request({ origins: [new URL(info.srcUrl).origin + '/*'] }).catch(() => false);
  }
  const s = await requireSettings();
  if (!s) return;
  try {
    switch (info.menuItemId) {
      case 'send-selection':
        if (info.selectionText) await sendText(s, info.selectionText);
        notify(`sent-${Date.now()}`, 'Sent to Clipverse ✓', (info.selectionText ?? '').slice(0, 120));
        break;
      case 'send-link':
        if (info.linkUrl) await sendText(s, info.linkUrl);
        notify(`sent-${Date.now()}`, 'Link sent to Clipverse ✓', info.linkUrl ?? '');
        break;
      case 'send-page':
        if (tab?.url) await sendText(s, tab.url);
        notify(`sent-${Date.now()}`, 'Page sent to Clipverse ✓', tab?.title ?? '');
        break;
      case 'send-image': {
        if (!info.srcUrl) break;
        const res = await fetch(info.srcUrl);
        if (!res.ok) throw new Error(`Could not download image (${res.status})`);
        let blob = await res.blob();
        if (!blob.type.startsWith('image/') || !['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/avif'].includes(blob.type)) {
          blob = await toPng(blob);
        }
        await sendImage(s, blob, 'web-image');
        notify(`sent-${Date.now()}`, 'Image sent to Clipverse ✓', new URL(info.srcUrl).hostname);
        break;
      }
    }
  } catch (e) {
    notify(`err-${Date.now()}`, 'Could not send to Clipverse', (e as Error).message);
  }
});

/** Converts SVG/BMP/ICO and other odd formats to PNG using OffscreenCanvas. */
async function toPng(blob: Blob): Promise<Blob> {
  const bmp = await createImageBitmap(blob);
  const canvas = new OffscreenCanvas(bmp.width, bmp.height);
  canvas.getContext('2d')!.drawImage(bmp, 0, 0);
  return canvas.convertToBlob({ type: 'image/png' });
}

// ---------- Wiring ----------

chrome.commands.onCommand.addListener((cmd) => {
  if (cmd === 'send-clipboard') void sendClipboard();
  if (cmd === 'pull-latest') void pullLatest();
});

chrome.notifications.onButtonClicked.addListener(async (id) => {
  const clip = pendingNotifications.get(id);
  if (clip && (await copyClipText(clip))) {
    notify(`copied-${Date.now()}`, 'Copied ✓', (clip.content ?? '').slice(0, 120));
  }
  chrome.notifications.clear(id);
});

chrome.notifications.onClicked.addListener((id) => {
  chrome.notifications.clear(id);
  pendingNotifications.delete(id);
  chrome.action.openPopup?.().catch(() => undefined);
});

chrome.notifications.onClosed.addListener((id) => pendingNotifications.delete(id));

chrome.runtime.onMessage.addListener((msg: Msg, _sender, sendResponse) => {
  if (msg.target !== 'background') return false;
  if (msg.type === 'get-config') {
    loadSettings().then((s) => sendResponse({ settings: isConfigured(s) ? s : null, fast: userActive }));
    return true;
  }
  if (msg.type === 'new-clips') void onNewClips(msg.clips);
  if (msg.type === 'settings-changed') {
    void pushConfig();
    loadSettings().then((s) => { if (isConfigured(s)) registerDevice(s).catch(() => undefined); });
  }
  return false;
});

chrome.idle.setDetectionInterval(60);
chrome.idle.onStateChanged.addListener((state) => {
  userActive = state === 'active';
  void pushConfig();
});

chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'keep-sync') void pushConfig(); });

/**
 * Zero-click setup: if the unpacked extension folder contains a `pairing.json`
 * ({"link": "clipt://pair?u=…&t=…"} or {"serverUrl": "…", "token": "…"}) and Clipverse isn't
 * configured yet, pair automatically. Handy for scripted installs; never shipped in releases.
 */
async function applyBundledPairing(): Promise<void> {
  if (isConfigured(await loadSettings())) return;
  let raw: { link?: string; serverUrl?: string; token?: string };
  try {
    const res = await fetch(chrome.runtime.getURL('pairing.json'));
    if (!res.ok) return;
    raw = await res.json();
  } catch {
    return; // no pairing.json — normal case
  }
  const parsed = raw.link ? parsePairingLink(raw.link) : raw.serverUrl && raw.token ? { serverUrl: raw.serverUrl, token: raw.token } : null;
  if (!parsed) return;
  const saved = await saveSettings(parsed);
  try {
    await registerDevice(saved);
    notify(`paired-${Date.now()}`, 'Clipverse connected ✓', `Syncing with ${new URL(saved.serverUrl).host}`);
  } catch (e) {
    notify(`pair-err-${Date.now()}`, 'Clipverse could not connect', (e as Error).message);
  }
}

async function boot() {
  await applyBundledPairing();
  setupMenus();
  await chrome.alarms.create('keep-sync', { periodInMinutes: 1 });
  await pushConfig();
}

chrome.runtime.onInstalled.addListener(() => void boot());
chrome.runtime.onStartup.addListener(() => void boot());
void applyBundledPairing().then(pushConfig);
