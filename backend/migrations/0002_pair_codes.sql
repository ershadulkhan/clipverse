-- One-time pairing codes: a paired device creates one, a new device redeems it once.
CREATE TABLE IF NOT EXISTS pair_codes (
  code       TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
-- Failed redeem attempts per client IP, for brute-force protection.
CREATE TABLE IF NOT EXISTS pair_fails (
  ip TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pair_fails ON pair_fails (ip, at);
