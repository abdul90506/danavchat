-- Danav Chat D1 schema
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  google_sub TEXT UNIQUE NOT NULL,
  email TEXT,
  username TEXT UNIQUE COLLATE NOCASE,
  name TEXT NOT NULL DEFAULT '',
  about TEXT NOT NULL DEFAULT 'Hey there! I am using Danav Chat.',
  avatar TEXT,
  fcm_token TEXT,
  last_seen INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_users_name ON users(name COLLATE NOCASE);
CREATE TABLE IF NOT EXISTS blocks (blocker TEXT NOT NULL, blocked TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY (blocker, blocked));
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY, sender TEXT NOT NULL, receiver TEXT NOT NULL, type TEXT NOT NULL, body TEXT, media TEXT,
  reply_to TEXT, created_at INTEGER NOT NULL, delivered_at INTEGER, read_at INTEGER,
  hidden INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_recv_upd ON messages(receiver, updated_at);
CREATE INDEX IF NOT EXISTS idx_msg_send_upd ON messages(sender, updated_at);
CREATE INDEX IF NOT EXISTS idx_msg_pair ON messages(sender, receiver, read_at);
CREATE TABLE IF NOT EXISTS deleted_for (user_id TEXT NOT NULL, message_id TEXT NOT NULL, PRIMARY KEY (user_id, message_id));
CREATE TABLE IF NOT EXISTS calls (
  id TEXT PRIMARY KEY, caller TEXT NOT NULL, callee TEXT NOT NULL, video INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL,
  created_at INTEGER NOT NULL, answered_at INTEGER, ended_at INTEGER, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_calls_caller ON calls(caller, updated_at);
CREATE INDEX IF NOT EXISTS idx_calls_callee ON calls(callee, updated_at);

-- Multi-member group conversations
CREATE TABLE IF NOT EXISTS chat_groups (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, avatar TEXT, created_by TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS group_members (
  group_id TEXT NOT NULL, user_id TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'member', joined_at INTEGER NOT NULL,
  PRIMARY KEY(group_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id,group_id);
CREATE TABLE IF NOT EXISTS group_messages (
  id TEXT PRIMARY KEY, group_id TEXT NOT NULL, sender TEXT NOT NULL, type TEXT NOT NULL, body TEXT, media TEXT,
  reply_to TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_group_messages ON group_messages(group_id,created_at);
CREATE TABLE IF NOT EXISTS group_deleted_for (user_id TEXT NOT NULL, message_id TEXT NOT NULL, PRIMARY KEY(user_id,message_id));

-- 24-hour stories/status updates
CREATE TABLE IF NOT EXISTS statuses (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL, type TEXT NOT NULL, media TEXT NOT NULL, caption TEXT,
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_status_expiry ON statuses(expires_at,user_id);
CREATE TABLE IF NOT EXISTS status_views (
  status_id TEXT NOT NULL, viewer_id TEXT NOT NULL, viewed_at INTEGER NOT NULL, PRIMARY KEY(status_id,viewer_id)
);
