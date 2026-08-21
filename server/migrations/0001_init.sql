-- Two tables. There is no users table (names are wrangler vars) and no
-- invites table (there is no pairing flow — see spec section 4).

CREATE TABLE devices (
  id         TEXT PRIMARY KEY,              -- uuid
  person     INTEGER NOT NULL CHECK (person IN (1,2)),
  auth_hash  TEXT NOT NULL UNIQUE,          -- SHA-256 of the bearer token
  fcm_token  TEXT,
  label      TEXT,                          -- "Giorgos · Xiaomi 13"
  created_at INTEGER NOT NULL,              -- epoch seconds
  updated_at INTEGER NOT NULL
);

CREATE INDEX idx_devices_person ON devices(person);

CREATE TABLE sends (
  id           TEXT PRIMARY KEY,            -- crypto.randomUUID()
  from_person  INTEGER NOT NULL,
  to_person    INTEGER NOT NULL,
  msg_id       INTEGER NOT NULL,
  sent_at      INTEGER NOT NULL,
  delivered_at INTEGER,                     -- her app posted the notification
  seen_at      INTEGER                      -- she tapped it
);

CREATE INDEX idx_sends_from_time ON sends(from_person, sent_at);
