-- Tabla de dispositivos identificados por UUIDv5 del User-Agent
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  user_agent TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

-- Relación dispositivo-usuario (muchos a muchos)
CREATE TABLE device_users (
  device_id TEXT NOT NULL REFERENCES devices(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  first_seen_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  PRIMARY KEY (device_id, user_id)
);

CREATE INDEX idx_device_users_user_id ON device_users(user_id);
