CREATE TABLE IF NOT EXISTS clips (
  id              TEXT PRIMARY KEY,
  type            TEXT NOT NULL CHECK (type IN ('text', 'image')),
  content         TEXT NOT NULL,
  mime            TEXT,
  size            INTEGER NOT NULL DEFAULT 0,
  device_id       TEXT,
  device_name     TEXT NOT NULL,
  device_platform TEXT NOT NULL CHECK (device_platform IN ('android', 'mac', 'windows', 'linux', 'chromeos', 'other')),
  is_pinned       INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_clips_created ON clips (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_clips_expiry ON clips (is_pinned, expires_at);

CREATE TABLE IF NOT EXISTS devices (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  platform   TEXT NOT NULL,
  fcm_token  TEXT,
  last_seen  INTEGER NOT NULL
);
