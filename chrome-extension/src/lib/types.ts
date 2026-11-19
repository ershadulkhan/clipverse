export type Platform = 'android' | 'mac' | 'windows' | 'linux' | 'chromeos' | 'other';

export interface Clip {
  id: string;
  type: 'text' | 'image';
  content: string | null;
  mime: string | null;
  size: number;
  device_id: string | null;
  device_name: string;
  device_platform: Platform;
  is_pinned: boolean;
  created_at: number;
  expires_at: number;
}

export interface Settings {
  serverUrl: string;
  token: string;
  deviceId: string;
  deviceName: string;
  platform: Platform;
  /** Put text that arrives from other devices straight onto this computer's clipboard. */
  autoReceive: boolean;
  /** Upload anything copied on this computer automatically. */
  autoSend: boolean;
  notify: boolean;
  theme: Theme;
  /** Master switch: when off, nothing syncs automatically (manual sending still works). */
  syncEnabled: boolean;
}

export type Theme = 'system' | 'light' | 'dark' | 'amoled';

/** Messages exchanged between popup, service worker and offscreen document. */
export type Msg =
  | { target: 'offscreen'; type: 'config'; settings: Settings | null; fast: boolean }
  | { target: 'offscreen'; type: 'read-clipboard' }
  | { target: 'offscreen'; type: 'write-clipboard'; text: string }
  | { target: 'background'; type: 'get-config' }
  | { target: 'background'; type: 'new-clips'; clips: Clip[] }
  | { target: 'background'; type: 'settings-changed' }
  | { target: 'popup'; type: 'clips-changed' };

export interface ClipboardPayload {
  text?: string;
  imageDataUrl?: string;
}
