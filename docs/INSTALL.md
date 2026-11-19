# Install Clipverse

A step-by-step guide for anyone who wants to use Clipverse on their own phone and computers.

> **How Clipverse keeps your data private:** every person runs their **own** free server on their **own** Cloudflare
> account. Your clips live only there, and only devices you pair can see them. Nobody else — not the person who
> made Clipverse, not other users — can see your clipboard, and you never use anyone else's storage.

**What you need**

| | |
|---|---|
| A computer | Windows, macOS, Linux or ChromeOS with Chrome, Edge, Brave, Arc or Vivaldi |
| An Android phone | Android 13 or newer |
| A free Cloudflare account | [dash.cloudflare.com/sign-up](https://dash.cloudflare.com/sign-up) — no credit card needed |
| A free GitHub account | only for the one-click server setup |

You only need **one server per person**. All your phones and computers share it.

---

## Step 1 · Download Clipverse

Open the [latest release](../../../releases/latest) and download both files under **Assets**:

- `Clipverse-Chrome-Extension-MV3.zip` — the browser extension
- `Clipverse-Android-Universal.apk` — the Android app (download this one on your phone)

(The server is set up straight from GitHub in Step 2 — you don't need to download it.)

## Step 2 · Set up your private server (once, about 5 minutes)

### Option A — one click (recommended, no terminal)

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/ershadulkhan/clipverse/tree/main/backend)

1. Click the button above. Sign in to (or create) your free **Cloudflare** account, and connect your **GitHub**
   account when asked — Cloudflare puts a copy of the server code there for you.
2. On the setup page, fill in **CLIPT_TOKEN**: make up a long random password (30+ letters and numbers).
   **Save it in your password manager — this is your sync token.** Leave everything else as it is.
3. Click **Create and deploy** and wait a minute or two. Cloudflare creates the database and storage for you.
4. Open the address it shows (like `https://clipt.yourname.workers.dev`). You should see **"Clipverse server ✓
   Running and ready"**. That address and your token are all you need for Step 3.

### Option B — terminal

Needs [Node.js 20+](https://nodejs.org). Unzip **Source code (zip)** from the release, open a terminal in its
`backend` folder and run:

```bash
npm install
npx wrangler login              # opens the browser — sign in to Cloudflare and click Allow
npx wrangler secret put CLIPT_TOKEN   # type a long random password = your sync token (save it!)
npm run deploy                  # creates the database + storage and prints your server address
```

If `secret put` asks to create the Worker first, answer **yes**.

## Step 3 · Install the extension on your computer

1. Unzip `Clipverse-Chrome-Extension-MV3.zip` into a folder you'll **keep**, with **no spaces** in its path:
   - Windows: `C:\Clipverse`
   - macOS: your home folder, e.g. `/Users/you/Clipverse`

   Don't delete or move this folder later — the browser loads the extension from it.
2. Open your browser's extensions page: type `chrome://extensions` in the address bar
   (Edge: `edge://extensions`, Brave: `brave://extensions`).
3. Turn on **Developer mode** (top-right switch).
4. Click **Load unpacked** (top-left) and select the folder from step 1 — the one that contains `manifest.json`.
5. A **Clipverse — Cloud Clipboard** card appears. Click the puzzle-piece icon in the toolbar and **pin** Clipverse.
6. Click the red Clipverse icon → **First device? Enter your server details** → enter your server address and sync
   token from Step 2 → **Connect**.

> Chrome may show "Disable developer mode extensions" on start-up. That's normal for extensions installed this way —
> click **✕** (not *Disable*).

## Step 4 · Install the app on your phone

1. On the phone, open the release page and download `Clipverse-Android-Universal.apk`.
2. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**, then tap **Install**.
   If Play Protect warns about an unknown developer, tap **More details → Install anyway**.
3. Open Clipverse and pair it:
   - On the computer: Clipverse icon → ⚙ Settings → **Pair another device**.
   - On the phone: tap **Scan QR to pair** and scan the QR — or type the short code shown under it
     (like `yourname-K7Q4MX`). The phone and computer can be on different networks.

## Step 5 · Add more computers (optional)

Repeat Step 3 on the new computer, but in step 6 just **type the pairing code** shown on any already-paired device
(Settings → **Pair another device**). Codes work once and expire after 10 minutes.

## Step 6 · Turn on automatic sync (optional)

Without this, you send clips by hand (share sheet, text selection → **Send to Clipverse**, Quick Settings tile,
or the popup). For copy-here-paste-there:

- **Computer → phone:** needs free Firebase push on your server — follow
  [Optional: instant push to Android](../README.md#optional-instant-push-to-android). Then on the phone:
  Settings → **Instant push from desktop**.
- **Phone → computer:** install [Shizuku](https://shizuku.rikka.app/download/) and start it (its app explains how;
  repeat after each phone restart). Then in Clipverse: Settings → **Auto-send what I copy** → allow Shizuku.
- On the computer, **Auto-send** and **Auto-paste** are on by default.
- The **Sync on / Paused** switch (top of the app and the popup) pauses everything on that device.

---

## Updating

- **Extension:** download the new zip, replace the files inside your extension folder, then click **↻** on the
  Clipverse card in `chrome://extensions`. Your pairing and settings are kept.
- **App:** download the new APK and install it over the old one — your pairing and settings are kept.
- **Server:** usually not needed — it only changes when a release says so. Then open the new release's
  **Source code (zip)**, and in its `backend` folder run `npm install`, `npx wrangler login` and `npm run deploy`
  (your data, token and settings are kept). If you used the one-click setup, make sure the Worker name you chose
  matches `name` in `wrangler.toml` first.

## Uninstalling

- **Extension:** `chrome://extensions` → Clipverse → **Remove**, then delete its folder.
- **App:** long-press Clipverse → **Uninstall**.
- **Server and all clips:** in the Cloudflare dashboard → **Workers & Pages**, delete your Clipverse Worker, then
  delete its D1 database and KV namespace (both start with the Worker's name).

## Privacy rules of thumb

- **Never share your sync token, QR code, pairing link or pairing code** with someone else — they'd be added to
  *your* clipboard and could read it. Friends should set up their own server (Step 2).
- To kick out every device at once, set a new token with `npx wrangler secret put CLIPT_TOKEN` and pair your own
  devices again.

## Troubleshooting

| Problem | Fix |
|---|---|
| **Load unpacked** adds nothing | Move the folder to a path without spaces (Step 3.1) and select the folder that contains `manifest.json` |
| "Wrong sync token" | Re-enter the token exactly as you set it in Step 2 |
| Phone says "Code not found or expired" | Codes last 10 minutes and work once — tap ↻ for a new one |
| App says Shizuku isn't running | Open Shizuku and start it again (needed after every phone restart) |
| Images take a few seconds to appear | Normal right after upload — the apps retry automatically |
