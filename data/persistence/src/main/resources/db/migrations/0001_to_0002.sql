PRAGMA foreign_keys = ON;

-- Phase A is an idempotent bootstrap transaction. It creates the durable ledger
-- before any version-changing side effect.
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS schema_migration_attempts (
  migration_id TEXT PRIMARY KEY,
  from_version INTEGER NOT NULL CHECK(from_version>=1),
  to_version INTEGER NOT NULL CHECK(to_version>from_version),
  plan_digest TEXT NOT NULL CHECK(length(plan_digest)=64),
  state TEXT NOT NULL CHECK(state IN ('STARTED','COMMITTED','ROLLED_BACK','FAILED','QUARANTINED')),
  started_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  error_code TEXT
);
COMMIT;

-- Phase B records STARTED before schema effects. This fixture uses a fixed
-- canonical plan digest; the runtime substitutes a generated migration artifact.
BEGIN IMMEDIATE;
INSERT INTO schema_migration_attempts(
  migration_id,from_version,to_version,plan_digest,state,started_at,updated_at,error_code
) VALUES(
  'MIG-001-TO-002',1,2,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  'STARTED','2026-08-02T00:00:00Z','2026-08-02T00:00:00Z',NULL
) ON CONFLICT(migration_id) DO NOTHING;
COMMIT;

-- Phase C is representative of the forward change. Production executes the
-- versioned, idempotent step list and verifies the final schema fingerprint.
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS schema_metadata (
  singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
  current_version INTEGER NOT NULL,
  min_readable_version INTEGER NOT NULL,
  min_writable_version INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('ACTIVE','QUARANTINED')),
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS schema_migration_history (
  migration_id TEXT PRIMARY KEY,
  from_version INTEGER NOT NULL,
  to_version INTEGER NOT NULL,
  plan_digest TEXT NOT NULL CHECK(length(plan_digest)=64),
  committed_at TEXT NOT NULL,
  FOREIGN KEY (migration_id) REFERENCES schema_migration_attempts(migration_id) ON DELETE RESTRICT
);
INSERT INTO schema_metadata VALUES(1,2,2,2,'ACTIVE','2026-08-02T00:00:00Z')
  ON CONFLICT(singleton_id) DO UPDATE SET
    current_version=excluded.current_version,
    min_readable_version=excluded.min_readable_version,
    min_writable_version=excluded.min_writable_version,
    state=excluded.state,
    updated_at=excluded.updated_at;
UPDATE schema_migration_attempts SET state='COMMITTED',updated_at='2026-08-02T00:00:00Z'
  WHERE migration_id='MIG-001-TO-002'
    AND plan_digest='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    AND state='STARTED';
INSERT INTO schema_migration_history VALUES(
  'MIG-001-TO-002',1,2,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  '2026-08-02T00:00:00Z'
);
COMMIT;
