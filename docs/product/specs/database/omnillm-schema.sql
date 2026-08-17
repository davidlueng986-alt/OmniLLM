PRAGMA foreign_keys = ON;

CREATE TABLE blobs (
  blob_id TEXT PRIMARY KEY CHECK(length(blob_id)=64),
  byte_length INTEGER NOT NULL CHECK(byte_length>=0),
  storage_key TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL
);

CREATE TABLE artifact_packages (
  artifact_package_id TEXT PRIMARY KEY CHECK(length(artifact_package_id)=64),
  schema_version INTEGER NOT NULL CHECK(schema_version>0),
  canonical_manifest_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE artifact_files (
  artifact_package_id TEXT NOT NULL,
  role TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  byte_length INTEGER NOT NULL CHECK(byte_length>=0),
  shard_index INTEGER NOT NULL CHECK(shard_index>=0),
  PRIMARY KEY (artifact_package_id, role, blob_id, shard_index),
  FOREIGN KEY (artifact_package_id) REFERENCES artifact_packages(artifact_package_id) ON DELETE CASCADE,
  FOREIGN KEY (blob_id) REFERENCES blobs(blob_id) ON DELETE RESTRICT
);

CREATE TABLE model_revisions (
  revision_id TEXT PRIMARY KEY CHECK(length(revision_id)=64),
  artifact_package_id TEXT NOT NULL,
  format_profile_json TEXT NOT NULL,
  quantization_descriptor_json TEXT NOT NULL,
  tokenizer_digest TEXT NOT NULL,
  template_digest TEXT NOT NULL,
  semantic_descriptor_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (artifact_package_id) REFERENCES artifact_packages(artifact_package_id) ON DELETE RESTRICT
);

CREATE TABLE installations (
  installation_id TEXT PRIMARY KEY,
  revision_id TEXT NOT NULL,
  state TEXT NOT NULL,
  storage_root_key TEXT NOT NULL UNIQUE,
  trust_epoch INTEGER NOT NULL DEFAULT 0 CHECK(trust_epoch>=0),
  template_epoch INTEGER NOT NULL DEFAULT 0 CHECK(template_epoch>=0),
  tokenizer_epoch INTEGER NOT NULL DEFAULT 0 CHECK(tokenizer_epoch>=0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (installation_id, revision_id),
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE RESTRICT
);

CREATE TABLE source_assertions (
  assertion_id TEXT PRIMARY KEY,
  source_type TEXT NOT NULL,
  schema_version INTEGER NOT NULL CHECK(schema_version>0),
  canonical_statement_json TEXT NOT NULL,
  statement_digest TEXT NOT NULL CHECK(length(statement_digest)=64),
  signer_id TEXT,
  signature_bytes BLOB,
  state TEXT NOT NULL,
  issued_at TEXT,
  expires_at TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE revision_assertions (
  revision_id TEXT NOT NULL,
  assertion_id TEXT NOT NULL,
  PRIMARY KEY (revision_id, assertion_id),
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE CASCADE,
  FOREIGN KEY (assertion_id) REFERENCES source_assertions(assertion_id) ON DELETE RESTRICT
);

CREATE TABLE installation_attestations (
  installation_id TEXT NOT NULL,
  revision_id TEXT NOT NULL,
  assertion_id TEXT NOT NULL,
  verified_at TEXT NOT NULL,
  verifier_build_id TEXT NOT NULL,
  result TEXT NOT NULL,
  PRIMARY KEY (installation_id, assertion_id),
  FOREIGN KEY (installation_id, revision_id) REFERENCES installations(installation_id, revision_id) ON DELETE CASCADE,
  FOREIGN KEY (revision_id, assertion_id) REFERENCES revision_assertions(revision_id, assertion_id) ON DELETE RESTRICT
);

CREATE TABLE license_terms (
  terms_id TEXT PRIMARY KEY,
  terms_digest TEXT NOT NULL UNIQUE CHECK(length(terms_digest)=64),
  canonical_text BLOB NOT NULL,
  locale TEXT,
  spdx_expression TEXT,
  source_url TEXT,
  version_label TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE license_events (
  event_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  revision_id TEXT NOT NULL,
  assertion_id TEXT,
  terms_id TEXT NOT NULL,
  action TEXT NOT NULL CHECK(action IN ('ACCEPT','REVOKE')),
  occurred_at TEXT NOT NULL,
  causation_id TEXT,
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE RESTRICT,
  FOREIGN KEY (assertion_id) REFERENCES source_assertions(assertion_id) ON DELETE RESTRICT,
  FOREIGN KEY (terms_id) REFERENCES license_terms(terms_id) ON DELETE RESTRICT
);

CREATE TABLE template_overrides (
  revision_id TEXT NOT NULL,
  version INTEGER NOT NULL CHECK(version>0),
  template_digest TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  state TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (revision_id, version),
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE CASCADE
);

CREATE TABLE reservations (
  reservation_id TEXT PRIMARY KEY,
  issuer_boot_id TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL CHECK(runtime_epoch>=0),
  principal_id TEXT NOT NULL,
  operation_id TEXT NOT NULL,
  state TEXT NOT NULL,
  steady_vector_json TEXT NOT NULL,
  peak_vector_json TEXT NOT NULL,
  deadline_elapsed_ns INTEGER NOT NULL CHECK(deadline_elapsed_ns>=0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE allocations (
  allocation_id TEXT PRIMARY KEY,
  reservation_id TEXT NOT NULL,
  owner_kind TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  state TEXT NOT NULL,
  resource_vector_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  released_at TEXT,
  UNIQUE (owner_kind, owner_id, allocation_id),
  FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id) ON DELETE RESTRICT
);

CREATE TABLE loaded_models (
  loaded_model_id TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL,
  engine_build_id TEXT NOT NULL,
  backend TEXT NOT NULL,
  device_fingerprint TEXT NOT NULL,
  load_key_digest TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL,
  state TEXT NOT NULL,
  allocation_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (installation_id) REFERENCES installations(installation_id) ON DELETE RESTRICT,
  FOREIGN KEY (allocation_id) REFERENCES allocations(allocation_id) ON DELETE RESTRICT
);

CREATE TABLE sessions (
  session_id TEXT PRIMARY KEY,
  loaded_model_id TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  session_epoch INTEGER NOT NULL CHECK(session_epoch>=0),
  state TEXT NOT NULL,
  committed_token_fingerprint TEXT,
  delivered_seq INTEGER NOT NULL DEFAULT 0 CHECK(delivered_seq>=0),
  allocation_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (loaded_model_id) REFERENCES loaded_models(loaded_model_id) ON DELETE RESTRICT,
  FOREIGN KEY (allocation_id) REFERENCES allocations(allocation_id) ON DELETE RESTRICT
);

CREATE TABLE revision_leases (
  lease_id TEXT PRIMARY KEY,
  revision_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL,
  state TEXT NOT NULL,
  expires_at TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE RESTRICT
);

CREATE TABLE inference_requests (
  request_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  operation_kind TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  canonical_request_digest TEXT NOT NULL,
  revision_id TEXT,
  state TEXT NOT NULL,
  resource_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (principal_id, operation_kind, idempotency_key),
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE RESTRICT
);

CREATE TABLE request_attempts (
  request_id TEXT NOT NULL,
  attempt_no INTEGER NOT NULL CHECK(attempt_no>=1),
  runtime_epoch INTEGER NOT NULL,
  worker_instance_id TEXT,
  state TEXT NOT NULL,
  started_at TEXT NOT NULL,
  ended_at TEXT,
  PRIMARY KEY (request_id, attempt_no),
  FOREIGN KEY (request_id) REFERENCES inference_requests(request_id) ON DELETE CASCADE
);

CREATE TABLE request_events (
  request_id TEXT NOT NULL,
  seq_from INTEGER NOT NULL CHECK(seq_from>=0),
  seq_to INTEGER NOT NULL CHECK(seq_to>=seq_from),
  event_kind TEXT NOT NULL,
  payload_digest TEXT,
  created_at TEXT NOT NULL,
  PRIMARY KEY (request_id, seq_from),
  FOREIGN KEY (request_id) REFERENCES inference_requests(request_id) ON DELETE CASCADE
);

CREATE TABLE request_terminals (
  request_id TEXT PRIMARY KEY,
  terminal_state TEXT NOT NULL,
  output_digest TEXT,
  error_code TEXT,
  terminal_seq INTEGER NOT NULL CHECK(terminal_seq>=0),
  completed_at TEXT NOT NULL,
  FOREIGN KEY (request_id) REFERENCES inference_requests(request_id) ON DELETE CASCADE
);

CREATE TABLE commit_records (
  commit_id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL,
  plan_id TEXT NOT NULL UNIQUE,
  canonical_input_digest TEXT NOT NULL,
  state TEXT NOT NULL,
  result_json TEXT,
  error_code TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (request_id) REFERENCES inference_requests(request_id) ON DELETE CASCADE
);

CREATE TABLE idempotent_commands (
  command_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  operation_kind TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_digest TEXT NOT NULL,
  state TEXT NOT NULL,
  result_json TEXT,
  error_code TEXT,
  resource_version INTEGER NOT NULL DEFAULT 0,
  expires_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (principal_id, operation_kind, idempotency_key)
);

CREATE TABLE jobs (
  job_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  job_kind TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  canonical_spec_json TEXT NOT NULL,
  state TEXT NOT NULL,
  resource_version INTEGER NOT NULL DEFAULT 0,
  checkpoint_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (principal_id, job_kind, idempotency_key)
);

CREATE TABLE job_attempts (
  job_id TEXT NOT NULL,
  attempt_no INTEGER NOT NULL CHECK(attempt_no>=1),
  state TEXT NOT NULL,
  started_at TEXT NOT NULL,
  ended_at TEXT,
  PRIMARY KEY (job_id, attempt_no),
  FOREIGN KEY (job_id) REFERENCES jobs(job_id) ON DELETE CASCADE
);

CREATE TABLE job_events (
  event_id INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id TEXT NOT NULL,
  attempt_no INTEGER,
  event_kind TEXT NOT NULL,
  payload_json TEXT,
  occurred_at TEXT NOT NULL,
  FOREIGN KEY (job_id) REFERENCES jobs(job_id) ON DELETE CASCADE,
  FOREIGN KEY (job_id, attempt_no) REFERENCES job_attempts(job_id, attempt_no) ON DELETE CASCADE
);

CREATE TABLE assets (
  asset_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  purpose TEXT NOT NULL,
  state TEXT NOT NULL,
  max_bytes INTEGER NOT NULL CHECK(max_bytes>0),
  actual_bytes INTEGER CHECK(actual_bytes>=0 AND actual_bytes<=max_bytes),
  expected_digest TEXT,
  actual_digest TEXT,
  content_type_hint TEXT,
  detected_content_type TEXT,
  storage_key TEXT,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE asset_references (
  asset_id TEXT NOT NULL,
  owner_kind TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  state TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (asset_id, owner_kind, owner_id),
  FOREIGN KEY (asset_id) REFERENCES assets(asset_id) ON DELETE CASCADE
);

CREATE TABLE download_attempts (
  attempt_id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  url_origin TEXT NOT NULL,
  validator_kind TEXT,
  validator_value TEXT,
  content_length INTEGER CHECK(content_length>=0),
  state TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (job_id) REFERENCES jobs(job_id) ON DELETE CASCADE
);

CREATE TABLE download_parts (
  attempt_id TEXT NOT NULL,
  file_key TEXT NOT NULL,
  range_start INTEGER NOT NULL CHECK(range_start>=0),
  range_end INTEGER NOT NULL CHECK(range_end>range_start),
  bytes_verified INTEGER NOT NULL DEFAULT 0 CHECK(bytes_verified>=0 AND bytes_verified<=range_end-range_start),
  temp_storage_key TEXT NOT NULL UNIQUE,
  PRIMARY KEY (attempt_id, file_key, range_start, range_end),
  FOREIGN KEY (attempt_id) REFERENCES download_attempts(attempt_id) ON DELETE CASCADE
);

CREATE TRIGGER download_parts_no_overlap_insert
BEFORE INSERT ON download_parts
WHEN EXISTS (
  SELECT 1 FROM download_parts p
  WHERE p.attempt_id=NEW.attempt_id AND p.file_key=NEW.file_key
    AND NOT (NEW.range_end<=p.range_start OR NEW.range_start>=p.range_end)
)
BEGIN
  SELECT RAISE(ABORT, 'overlapping download range');
END;

CREATE TABLE compatibility_evidence (
  evidence_id TEXT PRIMARY KEY,
  profile_id TEXT NOT NULL,
  canonical_profile_json TEXT NOT NULL,
  profile_digest TEXT NOT NULL CHECK(length(profile_digest)=64),
  result TEXT NOT NULL,
  method_version TEXT NOT NULL,
  measured_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE measurement_runs (
  run_id TEXT PRIMARY KEY,
  measurement_profile_id TEXT NOT NULL,
  canonical_profile_json TEXT NOT NULL,
  engine_build_id TEXT NOT NULL,
  device_fingerprint TEXT NOT NULL,
  revision_id TEXT NOT NULL,
  backend TEXT NOT NULL,
  method_version TEXT NOT NULL,
  measured_at TEXT NOT NULL,
  result_json TEXT NOT NULL,
  histogram_blob BLOB,
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE RESTRICT
);

CREATE TABLE client_registrations (
  registration_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  observed_uid INTEGER,
  android_user_id INTEGER,
  transport TEXT NOT NULL CHECK(transport IN ('LOCAL_UI','AIDL','HTTP_LOOPBACK','HTTP_LAN')),
  state TEXT NOT NULL CHECK(state IN ('PENDING','ACTIVE','SUSPENDED','REVOCATION_REQUESTED','DRAINING','REVOKED','EXPIRED')),
  scope_json TEXT NOT NULL,
  revocation_epoch INTEGER NOT NULL DEFAULT 0 CHECK(revocation_epoch>=0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE access_tokens (
  token_id TEXT PRIMARY KEY,
  registration_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('ISSUING','ACTIVE','REVOCATION_REQUESTED','DRAINING','REVOKED','EXPIRED','FAILED')),
  verifier BLOB NOT NULL,
  verifier_algorithm TEXT NOT NULL DEFAULT 'HMAC-SHA-256' CHECK(verifier_algorithm='HMAC-SHA-256'),
  verifier_key_version INTEGER NOT NULL CHECK(verifier_key_version>0),
  transport_constraint TEXT NOT NULL CHECK(transport_constraint IN ('LOOPBACK_ONLY','LAN_ONLY')),
  scope_json TEXT NOT NULL,
  revocation_epoch INTEGER NOT NULL DEFAULT 0 CHECK(revocation_epoch>=0),
  issued_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  last_seen_at TEXT,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (registration_id) REFERENCES client_registrations(registration_id) ON DELETE CASCADE
);

CREATE TABLE pairing_challenges (
  challenge_id TEXT PRIMARY KEY,
  challenge_kind TEXT NOT NULL CHECK(challenge_kind IN ('AIDL_REGISTRATION','LAN_HMAC')),
  principal_id TEXT,
  observed_uid INTEGER,
  android_user_id INTEGER,
  state TEXT NOT NULL CHECK(state IN ('PENDING','APPROVED','REJECTED','EXPIRED','CONSUMED')),
  protocol_label TEXT,
  requested_scope_json TEXT NOT NULL,
  server_spki_sha256 TEXT CHECK(server_spki_sha256 IS NULL OR length(server_spki_sha256)=64),
  connection_epoch INTEGER CHECK(connection_epoch IS NULL OR connection_epoch>=0),
  secret_ciphertext BLOB,
  secret_nonce BLOB,
  secret_key_version INTEGER CHECK(secret_key_version IS NULL OR secret_key_version>0),
  attempts_remaining INTEGER NOT NULL CHECK(attempts_remaining>=0 AND attempts_remaining<=5),
  expires_at TEXT NOT NULL,
  approved_at TEXT,
  consumed_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  CHECK((challenge_kind='LAN_HMAC' AND protocol_label='OmniLLM-LAN-Pairing-1' AND secret_ciphertext IS NOT NULL AND secret_nonce IS NOT NULL AND secret_key_version IS NOT NULL)
     OR (challenge_kind='AIDL_REGISTRATION' AND secret_ciphertext IS NULL AND secret_nonce IS NULL))
);

CREATE TABLE token_issue_receipts (
  issuance_key TEXT PRIMARY KEY,
  token_id TEXT NOT NULL,
  canonical_request_digest TEXT NOT NULL CHECK(length(canonical_request_digest)=64),
  token_ciphertext BLOB NOT NULL,
  token_nonce BLOB NOT NULL,
  key_version INTEGER NOT NULL CHECK(key_version>0),
  expires_at TEXT NOT NULL,
  acknowledged_at TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (token_id) REFERENCES access_tokens(token_id) ON DELETE CASCADE
);

CREATE TABLE pairing_exchanges (
  exchange_id TEXT PRIMARY KEY,
  challenge_id TEXT NOT NULL,
  client_public_key_digest TEXT NOT NULL CHECK(length(client_public_key_digest)=64),
  canonical_request_digest TEXT NOT NULL CHECK(length(canonical_request_digest)=64),
  state TEXT NOT NULL CHECK(state IN ('RECEIVED','VERIFYING','SUCCEEDED','FAILED','EXPIRED')),
  token_id TEXT,
  issuance_key TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (challenge_id) REFERENCES pairing_challenges(challenge_id) ON DELETE RESTRICT,
  FOREIGN KEY (token_id) REFERENCES access_tokens(token_id) ON DELETE RESTRICT,
  FOREIGN KEY (issuance_key) REFERENCES token_issue_receipts(issuance_key) ON DELETE RESTRICT
);

CREATE TABLE content_reports (
  report_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('DRAFT','CONSENTED','QUEUED','SUBMITTING','FAILED_RETRYABLE','SUBMITTED','CANCELLED','EXPIRED')),
  category TEXT NOT NULL,
  payload_preview_digest TEXT NOT NULL CHECK(length(payload_preview_digest)=64),
  encrypted_payload BLOB,
  user_confirmed_at TEXT,
  expires_at TEXT NOT NULL,
  receipt_id TEXT,
  error_code TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (principal_id, idempotency_key)
);

CREATE TABLE security_audit_events (
  event_id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_class TEXT NOT NULL,
  principal_pseudonym TEXT,
  action TEXT NOT NULL,
  target_digest TEXT,
  detail_json TEXT,
  occurred_at TEXT NOT NULL,
  retention_class TEXT NOT NULL
);

CREATE TABLE catalog_trust_state (
  singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
  schema_version INTEGER NOT NULL CHECK(schema_version>0),
  root_version INTEGER NOT NULL CHECK(root_version>=0),
  timestamp_version INTEGER NOT NULL CHECK(timestamp_version>=0),
  snapshot_version INTEGER NOT NULL CHECK(snapshot_version>=0),
  targets_version INTEGER NOT NULL CHECK(targets_version>=0),
  last_trusted_wall_time TEXT,
  boot_id TEXT,
  elapsed_anchor_ns INTEGER CHECK(elapsed_anchor_ns>=0),
  clock_state TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX idx_installations_revision_state ON installations(revision_id,state);
CREATE INDEX idx_loaded_models_installation_state ON loaded_models(installation_id,state);
CREATE INDEX idx_sessions_model_owner_state ON sessions(loaded_model_id,principal_id,state);
CREATE INDEX idx_requests_principal_state ON inference_requests(principal_id,state,created_at);
CREATE INDEX idx_jobs_principal_state ON jobs(principal_id,state,created_at);
CREATE INDEX idx_job_events_job_event ON job_events(job_id,event_id);
CREATE INDEX idx_security_event_time ON security_audit_events(occurred_at,event_class);

CREATE INDEX idx_pairing_challenge_expiry ON pairing_challenges(state,expires_at);
CREATE INDEX idx_access_token_registration ON access_tokens(registration_id,state,expires_at);
CREATE INDEX idx_token_receipt_expiry ON token_issue_receipts(expires_at);
