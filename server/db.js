import Database from 'better-sqlite3'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

export function openDatabase(file) {
  mkdirSync(dirname(file), { recursive: true })
  const db = new Database(file)
  db.pragma('journal_mode = WAL')
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      username TEXT PRIMARY KEY,
      password_hash TEXT NOT NULL,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS installs (
      install_id TEXT PRIMARY KEY,
      first_seen TEXT NOT NULL,
      last_seen  TEXT NOT NULL,
      app_version TEXT,
      device TEXT,
      android TEXT
    );

    CREATE TABLE IF NOT EXISTS downloads (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      at TEXT NOT NULL,
      user_agent TEXT,
      ip_prefix TEXT
    );

    CREATE TABLE IF NOT EXISTS sessions (
      session_id TEXT PRIMARY KEY,
      install_id TEXT NOT NULL,
      started_at TEXT NOT NULL,
      ended_at TEXT,
      environment TEXT,
      ball TEXT,
      time_of_day TEXT,
      course TEXT,
      course_par INTEGER,
      holes_played INTEGER DEFAULT 0,
      through_par INTEGER DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS shots (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      session_id TEXT NOT NULL,
      struck_at TEXT NOT NULL,
      hole INTEGER,
      shot_number INTEGER,
      club TEXT,
      lie TEXT,
      ball_speed_ms REAL,
      launch_deg REAL,
      offline_deg REAL,
      curve_deg REAL,
      carry_m REAL,
      lateral_m REAL,
      apex_m REAL,
      score INTEGER,
      from_lat REAL, from_lon REAL,
      to_lat REAL, to_lon REAL,
      to_green_m REAL,
      track TEXT,
      UNIQUE (session_id, hole, shot_number)
    );

    CREATE INDEX IF NOT EXISTS shots_by_session ON shots (session_id);
    CREATE INDEX IF NOT EXISTS sessions_by_install ON sessions (install_id);
  `)
  return db
}
