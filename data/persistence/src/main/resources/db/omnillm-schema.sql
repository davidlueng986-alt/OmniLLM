PRAGMA foreign_keys = ON;

-- ===========================================================================
-- OmniLLM control-plane authority schema (API-40..44 sync, 2026-08-09).
--
-- Table status legend:
--   -- STATUS: IMPLEMENTED                        → SQLDelight .sq projection
--     exists under data/persistence/src/main/sqldelight/ (see the named .sq).
--   -- STATUS: PLANNED (not yet implemented)      → spec-only; no .sq
--     projection exists yet. Do NOT treat as available storage surface.
--
-- Authority flows ONE way: implementations project from this file. The .sq
-- files are the implemented truth for the tables they cover; this file has
-- been realigned to match them (columns, nullability, defaults, timestamps
-- as epoch_ms INTEGER, explicit composite PKs).
-- ===========================================================================

-- STATUS: IMPLEMENTED — SQLDelight .sq: SchemaMetadata.sq
CREATE TABLE schema_metadata (
  singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
  current_version INTEGER NOT NULL CHECK(current_version>=1),
  min_readable_version INTEGER NOT NULL CHECK(min_readable_version>=1 AND min_readable_version<=current_version),
  min_writable_version INTEGER NOT NULL CHECK(min_writable_version>=min_readable_version AND min_writable_version<=current_version),
  state TEXT NOT NULL CHECK(state IN ('ACTIVE','QUARANTINED')),
  updated_at TEXT NOT NULL
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: SchemaMigrationAttempts.sq
CREATE TABLE schema_migration_attempts (
  migration_id TEXT PRIMARY KEY,
  from_version INTEGER NOT NULL CHECK(from_version>=1),
  to_version INTEGER NOT NULL CHECK(to_version>from_version),
  plan_digest TEXT NOT NULL CHECK(length(plan_digest)=64),
  state TEXT NOT NULL CHECK(state IN ('STARTED','COMMITTED','ROLLED_BACK','FAILED','QUARANTINED')),
  started_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  error_code TEXT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: SchemaMigrationHistory.sq
CREATE TABLE schema_migration_history (
  migration_id TEXT PRIMARY KEY,
  from_version INTEGER NOT NULL,
  to_version INTEGER NOT NULL,
  plan_digest TEXT NOT NULL CHECK(length(plan_digest)=64),
  committed_at TEXT NOT NULL,
  FOREIGN KEY (migration_id) REFERENCES schema_migration_attempts(migration_id) ON DELETE RESTRICT
);

CREATE TRIGGER migration_history_requires_committed_attempt
BEFORE INSERT ON schema_migration_history
WHEN NOT EXISTS (
  SELECT 1 FROM schema_migration_attempts a
  WHERE a.migration_id=NEW.migration_id
    AND a.from_version=NEW.from_version
    AND a.to_version=NEW.to_version
    AND a.plan_digest=NEW.plan_digest
    AND a.state='COMMITTED'
)
BEGIN
  SELECT RAISE(ABORT, 'migration history requires committed matching attempt');
END;

INSERT INTO schema_metadata(singleton_id,current_version,min_readable_version,min_writable_version,state,updated_at)
VALUES(1,2,2,2,'ACTIVE','2026-08-02T00:00:00Z');

-- STATUS: PLANNED (not yet implemented)
CREATE TABLE runtime_instances (
  runtime_instance_id TEXT PRIMARY KEY,
  boot_id TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL UNIQUE CHECK(runtime_epoch>=0),
  state TEXT NOT NULL CHECK(state IN ('STOPPED','STARTING','RECOVERING','READY','DEGRADED','DRAINING','WAITING_FOR_USER_FOREGROUND','FAULTED','FENCED')),
  started_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  fenced_at TEXT,
  UNIQUE (boot_id, runtime_epoch)
);

-- STATUS: PLANNED (not yet implemented)
CREATE TABLE blobs (
  blob_id TEXT PRIMARY KEY CHECK(length(blob_id)=64),
  byte_length INTEGER NOT NULL CHECK(byte_length>=0),
  storage_key TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL
);

-- STATUS: PLANNED (not yet implemented)
CREATE TABLE artifact_packages (
  artifact_package_id TEXT PRIMARY KEY CHECK(length(artifact_package_id)=64),
  schema_version INTEGER NOT NULL CHECK(schema_version>0),
  canonical_manifest_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

-- STATUS: PLANNED (not yet implemented)
CREATE TABLE artifact_files (
  artifact_package_id TEXT NOT NULL,
  role TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  shard_index INTEGER NOT NULL CHECK(shard_index>=0),
  PRIMARY KEY (artifact_package_id, role, blob_id, shard_index),
  UNIQUE (artifact_package_id, role, shard_index),
  FOREIGN KEY (artifact_package_id) REFERENCES artifact_packages(artifact_package_id) ON DELETE CASCADE,
  FOREIGN KEY (blob_id) REFERENCES blobs(blob_id) ON DELETE RESTRICT
);

-- STATUS: PLANNED (not yet implemented)
CREATE TABLE model_revisions (
  revision_id TEXT PRIMARY KEY CHECK(length(revision_id)=64),
  schema_version INTEGER NOT NULL CHECK(schema_version=1),
  artifact_package_id TEXT NOT NULL,
  format_profile_json TEXT NOT NULL,
  quantization_descriptor_json TEXT NOT NULL,
  tokenizer_digest TEXT NOT NULL CHECK(length(tokenizer_digest)=64),
  template_digest TEXT NOT NULL CHECK(length(template_digest)=64),
  semantic_descriptor_json TEXT NOT NULL,
  tensor_layout_policy_digest TEXT NOT NULL CHECK(length(tensor_layout_policy_digest)=64),
  model_metadata_schema_version INTEGER NOT NULL CHECK(model_metadata_schema_version>0),
  created_at TEXT NOT NULL,
  FOREIGN KEY (artifact_package_id) REFERENCES artifact_packages(artifact_package_id) ON DELETE RESTRICT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: Installations.sq
CREATE TABLE installations (
  installation_id TEXT PRIMARY KEY,
  revision_id TEXT NOT NULL,
  artifact_package_id TEXT NOT NULL,
  state TEXT NOT NULL,
  storage_root_key TEXT NOT NULL UNIQUE,
  trust_epoch INTEGER NOT NULL DEFAULT 0 CHECK(trust_epoch>=0),
  template_epoch INTEGER NOT NULL DEFAULT 0 CHECK(template_epoch>=0),
  tokenizer_epoch INTEGER NOT NULL DEFAULT 0 CHECK(tokenizer_epoch>=0),
  quarantine_job_id TEXT,
  quarantine_attempt_id TEXT,
  authenticity_ok INTEGER,
  license_ok INTEGER,
  compatibility_ok INTEGER,
  performance_recorded INTEGER,
  placement_class TEXT,
  pinned INTEGER NOT NULL DEFAULT 0,
  reject_reason TEXT,
  resource_version INTEGER NOT NULL DEFAULT 0 CHECK(resource_version>=0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (installation_id, revision_id),
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE RESTRICT
);

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
CREATE TABLE revision_assertions (
  revision_id TEXT NOT NULL,
  assertion_id TEXT NOT NULL,
  PRIMARY KEY (revision_id, assertion_id),
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE CASCADE,
  FOREIGN KEY (assertion_id) REFERENCES source_assertions(assertion_id) ON DELETE RESTRICT
);

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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
  updated_at TEXT NOT NULL,
  FOREIGN KEY (issuer_boot_id, runtime_epoch) REFERENCES runtime_instances(boot_id, runtime_epoch) ON DELETE RESTRICT
);

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: IMPLEMENTED — SQLDelight .sq: Sessions.sq
CREATE TABLE sessions (
  session_id TEXT PRIMARY KEY,
  session_epoch INTEGER NOT NULL CHECK(session_epoch>=0),
  owner_key TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  loaded_model_id TEXT NOT NULL,
  model_revision_id TEXT NOT NULL,
  engine_build_id TEXT NOT NULL,
  backend TEXT NOT NULL,
  device_execution_fingerprint TEXT NOT NULL,
  template_epoch INTEGER NOT NULL,
  tokenizer_epoch INTEGER NOT NULL,
  load_configuration_digest TEXT NOT NULL,
  tokenizer_digest TEXT NOT NULL,
  context_config TEXT NOT NULL,
  committed_token_fingerprint TEXT,
  state TEXT NOT NULL,
  allocation_id TEXT NOT NULL,
  revocation_epoch INTEGER NOT NULL,
  runtime_epoch INTEGER NOT NULL,
  recovery_disposition TEXT NOT NULL,
  healthy INTEGER NOT NULL DEFAULT 1 CHECK(healthy IN (0,1)),
  pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
  delivered_seq INTEGER NOT NULL DEFAULT 0 CHECK(delivered_seq>=0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (loaded_model_id) REFERENCES loaded_models(loaded_model_id) ON DELETE RESTRICT,
  FOREIGN KEY (allocation_id) REFERENCES allocations(allocation_id) ON DELETE RESTRICT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: RevisionLeases.sq
CREATE TABLE revision_leases (
  lease_id TEXT PRIMARY KEY,
  revision_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL,
  state TEXT NOT NULL,
  installation_id TEXT,
  reference_count INTEGER NOT NULL DEFAULT 1 CHECK(reference_count>=1),
  expires_at TEXT,
  expires_at_monotonic INTEGER,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (revision_id) REFERENCES model_revisions(revision_id) ON DELETE RESTRICT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: InferenceRequests.sq
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

-- STATUS: IMPLEMENTED — SQLDelight .sq: RequestAttempts.sq
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: IMPLEMENTED — SQLDelight .sq: RequestTerminals.sq
CREATE TABLE request_terminals (
  request_id TEXT PRIMARY KEY,
  terminal_state TEXT NOT NULL,
  output_digest TEXT,
  error_code TEXT,
  terminal_seq INTEGER NOT NULL CHECK(terminal_seq>=0),
  completed_at TEXT NOT NULL,
  FOREIGN KEY (request_id) REFERENCES inference_requests(request_id) ON DELETE CASCADE
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: CommitRecords.sq
CREATE TABLE commit_records (
  commit_id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  plan_id TEXT NOT NULL UNIQUE,
  reservation_id TEXT NOT NULL,
  revision_lease_id TEXT NOT NULL,
  issuer_boot_id TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL CHECK(runtime_epoch>=0),
  revocation_epoch INTEGER NOT NULL CHECK(revocation_epoch>=0),
  source_session_epoch INTEGER CHECK(source_session_epoch IS NULL OR source_session_epoch>=0),
  target_policy_digest TEXT NOT NULL CHECK(length(target_policy_digest)=64),
  engine_build_id TEXT NOT NULL,
  canonical_input_digest TEXT NOT NULL CHECK(length(canonical_input_digest)=64),
  commit_nonce_digest TEXT NOT NULL UNIQUE CHECK(length(commit_nonce_digest)=64),
  state TEXT NOT NULL CHECK(state IN ('INTENT_RECORDED','EXECUTING','RESULT_RECORDED','RECONCILING','COMMITTED','ABORTED','UNCERTAIN_QUARANTINED')),
  result_json TEXT,
  error_code TEXT,
  reconciliation_disposition TEXT CHECK(reconciliation_disposition IS NULL OR reconciliation_disposition IN ('RESULT_FOUND','ROLLED_BACK','POISONED','QUARANTINED','UNPROVABLE')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (request_id) REFERENCES inference_requests(request_id) ON DELETE CASCADE,
  FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id) ON DELETE RESTRICT,
  FOREIGN KEY (revision_lease_id) REFERENCES revision_leases(lease_id) ON DELETE RESTRICT,
  FOREIGN KEY (issuer_boot_id, runtime_epoch) REFERENCES runtime_instances(boot_id, runtime_epoch) ON DELETE RESTRICT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: PreparedOperations.sq
CREATE TABLE prepared_operations (
  prepared_operation_id TEXT PRIMARY KEY,
  operation_id TEXT NOT NULL UNIQUE,
  request_id TEXT NOT NULL,
  commit_id TEXT NOT NULL UNIQUE,
  principal_id TEXT NOT NULL,
  reservation_id TEXT NOT NULL,
  revision_lease_id TEXT NOT NULL,
  issuer_boot_id TEXT NOT NULL,
  runtime_epoch INTEGER NOT NULL CHECK(runtime_epoch>=0),
  revocation_epoch INTEGER NOT NULL CHECK(revocation_epoch>=0),
  source_session_id TEXT,
  source_session_epoch INTEGER CHECK(source_session_epoch IS NULL OR source_session_epoch>=0),
  target_session_id TEXT,
  canonical_input_digest TEXT NOT NULL CHECK(length(canonical_input_digest)=64),
  state TEXT NOT NULL CHECK(state IN ('PREPARED','STARTING','RUNNING','CANCELLING','RECONCILING','COMPLETED','FAILED','CANCELLED','ABORTED_UNCERTAIN')),
  start_claimed_at TEXT,
  result_json TEXT,
  error_code TEXT,
  reconciliation_disposition TEXT CHECK(reconciliation_disposition IS NULL OR reconciliation_disposition IN ('NOT_REQUIRED','RESULT_FOUND','ROLLED_BACK','POISONED','QUARANTINED','UNPROVABLE')),
  resource_version INTEGER NOT NULL DEFAULT 0 CHECK(resource_version>=0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (request_id) REFERENCES inference_requests(request_id) ON DELETE CASCADE,
  FOREIGN KEY (commit_id) REFERENCES commit_records(commit_id) ON DELETE RESTRICT,
  FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id) ON DELETE RESTRICT,
  FOREIGN KEY (revision_lease_id) REFERENCES revision_leases(lease_id) ON DELETE RESTRICT,
  FOREIGN KEY (issuer_boot_id, runtime_epoch) REFERENCES runtime_instances(boot_id, runtime_epoch) ON DELETE RESTRICT,
  FOREIGN KEY (source_session_id) REFERENCES sessions(session_id) ON DELETE RESTRICT,
  FOREIGN KEY (target_session_id) REFERENCES sessions(session_id) ON DELETE RESTRICT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: CommitResourceBindings.sq
CREATE TABLE commit_resource_bindings (
  commit_id TEXT NOT NULL,
  allocation_id TEXT NOT NULL,
  binding_role TEXT NOT NULL CHECK(binding_role IN ('SOURCE','TARGET','TRANSFERRED','REMAINDER')),
  resource_vector_digest TEXT NOT NULL CHECK(length(resource_vector_digest)=64),
  disposition TEXT NOT NULL CHECK(disposition IN ('PREPARED','TRANSFERRED','RELEASED','QUARANTINED')),
  updated_at TEXT NOT NULL,
  PRIMARY KEY (commit_id, allocation_id, binding_role),
  FOREIGN KEY (commit_id) REFERENCES commit_records(commit_id) ON DELETE CASCADE,
  FOREIGN KEY (allocation_id) REFERENCES allocations(allocation_id) ON DELETE RESTRICT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: IdempotentCommands.sq
CREATE TABLE idempotent_commands (
  command_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  operation_kind TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  expected_version INTEGER CHECK(expected_version IS NULL OR expected_version>=0),
  canonical_input_digest TEXT NOT NULL CHECK(length(canonical_input_digest)=64),
  state TEXT NOT NULL CHECK(state IN ('RECEIVED','CLAIMED','RUNNING','RECONCILING','SUCCEEDED','FAILED','CANCELLED','UNCERTAIN')),
  affected_resource_id TEXT,
  result_json TEXT,
  error_code TEXT,
  reconciliation_disposition TEXT CHECK(reconciliation_disposition IS NULL OR reconciliation_disposition IN ('NOT_REQUIRED','RESULT_FOUND','ROLLED_BACK','QUARANTINED','UNPROVABLE')),
  resource_version INTEGER NOT NULL DEFAULT 0,
  expires_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (principal_id, operation_kind, idempotency_key)
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: Jobs.sq
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

-- STATUS: IMPLEMENTED — SQLDelight .sq: JobAttempts.sq
CREATE TABLE job_attempts (
  job_id TEXT NOT NULL,
  attempt_no INTEGER NOT NULL CHECK(attempt_no>=1),
  state TEXT NOT NULL,
  started_at TEXT NOT NULL,
  ended_at TEXT,
  PRIMARY KEY (job_id, attempt_no),
  FOREIGN KEY (job_id) REFERENCES jobs(job_id) ON DELETE CASCADE
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: JobEvents.sq
-- event_id is explicitly assigned by JobManager (not AUTOINCREMENT-only);
-- PK is (event_id, job_id) per the implemented projection.
CREATE TABLE job_events (
  event_id INTEGER NOT NULL,
  job_id TEXT NOT NULL,
  attempt_no INTEGER,
  event_kind TEXT NOT NULL,
  payload_json TEXT,
  occurred_at TEXT NOT NULL,
  PRIMARY KEY (event_id, job_id),
  FOREIGN KEY (job_id) REFERENCES jobs(job_id) ON DELETE CASCADE,
  FOREIGN KEY (job_id, attempt_no) REFERENCES job_attempts(job_id, attempt_no) ON DELETE CASCADE
);

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
CREATE TABLE asset_references (
  asset_id TEXT NOT NULL,
  owner_kind TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  state TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (asset_id, owner_kind, owner_id),
  FOREIGN KEY (asset_id) REFERENCES assets(asset_id) ON DELETE CASCADE
);

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: IMPLEMENTED — SQLDelight .sq: AccessTokens.sq
CREATE TABLE access_tokens (
  token_id TEXT PRIMARY KEY,
  registration_id TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('ISSUING','ACTIVE','REVOCATION_REQUESTED','DRAINING','REVOKED','EXPIRED','FAILED')),
  verifier BLOB NOT NULL,
  verifier_algorithm TEXT NOT NULL,
  verifier_key_version INTEGER NOT NULL CHECK(verifier_key_version>0),
  transport_constraint TEXT NOT NULL CHECK(transport_constraint IN ('LOOPBACK_ONLY','LAN_ONLY')),
  scope_json TEXT NOT NULL,
  revocation_epoch INTEGER NOT NULL CHECK(revocation_epoch>=0),
  issued_at_epoch_ms INTEGER NOT NULL,
  expires_at_epoch_ms INTEGER NOT NULL,
  last_seen_at_epoch_ms INTEGER,
  label TEXT,
  client_id TEXT,
  updated_at_epoch_ms INTEGER NOT NULL,
  FOREIGN KEY (registration_id) REFERENCES client_registrations(registration_id) ON DELETE CASCADE
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: PairingChallenges.sq
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
  server_nonce TEXT,
  secret_ciphertext BLOB,
  secret_nonce BLOB,
  secret_key_version INTEGER CHECK(secret_key_version IS NULL OR secret_key_version>0),
  secret_expires_at_epoch_ms INTEGER,
  secret_schema_version INTEGER,
  attempts_remaining INTEGER NOT NULL CHECK(attempts_remaining>=0 AND attempts_remaining<=5),
  expires_at_epoch_ms INTEGER NOT NULL,
  approved_at_epoch_ms INTEGER,
  consumed_at_epoch_ms INTEGER,
  created_at_epoch_ms INTEGER NOT NULL,
  updated_at_epoch_ms INTEGER NOT NULL
);

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: IMPLEMENTED — SQLDelight .sq: SecretBrokerKeys.sq
CREATE TABLE secret_broker_keys (
  purpose TEXT NOT NULL,
  key_version INTEGER NOT NULL,
  state TEXT NOT NULL,
  created_at_epoch_ms INTEGER NOT NULL,
  rotation_reason TEXT,
  key_storage TEXT NOT NULL,
  keystore_alias TEXT,
  key_ciphertext BLOB,
  PRIMARY KEY (purpose, key_version)
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: RevocationSubjects.sq
CREATE TABLE revocation_subjects (
  scope_key TEXT NOT NULL PRIMARY KEY,
  subject_kind TEXT NOT NULL,
  subject_id TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  state TEXT NOT NULL,
  reason TEXT,
  actor_principal_id TEXT,
  updated_at_epoch_ms INTEGER NOT NULL
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: ContentReports.sq
CREATE TABLE content_reports (
  report_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  proposal_command_id TEXT NOT NULL UNIQUE,
  idempotency_key TEXT NOT NULL,
  resource_version INTEGER NOT NULL DEFAULT 0 CHECK(resource_version>=0),
  state TEXT NOT NULL CHECK(state IN ('DRAFT','REVIEWING','CONSENT_GRANTED','QUEUED_OFFLINE','SUBMITTING','CANCELLING','RECONCILING','FAILED_RETRYABLE','FAILED_FINAL','SUBMITTED','DISCARDED','EXPIRED')),
  category TEXT NOT NULL CHECK(category IN ('HATE_HARASSMENT','SEXUAL_CONTENT','CHILD_SAFETY','VIOLENCE_SELF_HARM','ILLEGAL_ACTIVITY','DECEPTION_IMPERSONATION','PRIVACY_PERSONAL_DATA','DANGEROUS_ADVICE','OTHER')),
  app_build TEXT NOT NULL,
  model_revision_id TEXT NOT NULL CHECK(length(model_revision_id)=64),
  engine_build_id TEXT NOT NULL,
  backend TEXT NOT NULL,
  local_policy_version TEXT NOT NULL,
  output_digest TEXT NOT NULL CHECK(length(output_digest)=64),
  user_locale TEXT NOT NULL,
  encrypted_proposal BLOB NOT NULL,
  canonical_payload_digest TEXT CHECK(canonical_payload_digest IS NULL OR length(canonical_payload_digest)=64),
  encrypted_payload BLOB,
  cancel_pending INTEGER NOT NULL DEFAULT 0 CHECK(cancel_pending IN (0,1)),
  expires_at TEXT NOT NULL,
  error_code TEXT,
  active_grant_id TEXT,
  receipt_id TEXT,
  receipt_accepted_at TEXT,
  receipt_status_url TEXT,
  payload_created_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (principal_id, idempotency_key),
  FOREIGN KEY (proposal_command_id) REFERENCES idempotent_commands(command_id) ON DELETE RESTRICT
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: ContentReportConsentGrants.sq
CREATE TABLE content_report_consent_grants (
  grant_id TEXT PRIMARY KEY,
  report_id TEXT NOT NULL,
  principal_id TEXT NOT NULL,
  canonical_payload_digest TEXT NOT NULL CHECK(length(canonical_payload_digest)=64),
  warning_policy_version TEXT NOT NULL,
  local_user_profile_id TEXT NOT NULL,
  nonce TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK(state IN ('ISSUED','CONSUMED','EXPIRED','REVOKED')),
  issued_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  FOREIGN KEY (report_id) REFERENCES content_reports(report_id) ON DELETE CASCADE,
  UNIQUE (report_id, grant_id),
  CHECK((state='CONSUMED' AND consumed_at IS NOT NULL) OR (state!='CONSUMED' AND consumed_at IS NULL))
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: ContentReportReceipts.sq
CREATE TABLE content_report_receipts (
  receipt_id TEXT PRIMARY KEY,
  report_id TEXT NOT NULL UNIQUE,
  accepted_at TEXT NOT NULL,
  status_url TEXT NOT NULL,
  response_digest TEXT NOT NULL CHECK(length(response_digest)=64),
  last_queried_at TEXT,
  FOREIGN KEY (report_id) REFERENCES content_reports(report_id) ON DELETE CASCADE
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: ToolProposals.sq
CREATE TABLE tool_proposals (
  proposal_id TEXT NOT NULL PRIMARY KEY,
  request_id TEXT NOT NULL,
  tool_id TEXT NOT NULL,
  schema_digest TEXT NOT NULL,
  arguments_json TEXT NOT NULL,
  attempt INTEGER NOT NULL DEFAULT 1,
  state TEXT NOT NULL,
  created_at_epoch_ms INTEGER NOT NULL
);

-- STATUS: IMPLEMENTED — SQLDelight .sq: ToolResultClaims.sq
CREATE TABLE tool_result_claims (
  proposal_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_id TEXT NOT NULL,
  attempt INTEGER NOT NULL,
  result_payload_digest TEXT NOT NULL,
  is_error INTEGER NOT NULL DEFAULT 0,
  submitted_at_epoch_ms INTEGER NOT NULL,
  PRIMARY KEY (proposal_id, idempotency_key)
);

-- STATUS: PLANNED (not yet implemented)
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

-- STATUS: IMPLEMENTED — SQLDelight .sq: CatalogTrustState.sq
CREATE TABLE catalog_trust_state (
  singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
  highest_sequence INTEGER NOT NULL DEFAULT 0 CHECK(highest_sequence>=0),
  trusted_clock_epoch_ms INTEGER,
  boot_id TEXT,
  elapsed_realtime_anchor_ms INTEGER,
  uncertain INTEGER NOT NULL DEFAULT 1 CHECK(uncertain IN (0,1)),
  root_digest TEXT,
  updated_at TEXT NOT NULL
);

CREATE INDEX idx_installations_revision_state ON installations(revision_id,state);
CREATE INDEX idx_loaded_models_installation_state ON loaded_models(installation_id,state);
CREATE INDEX idx_sessions_model_owner_state ON sessions(loaded_model_id,principal_id,state);
CREATE INDEX idx_requests_principal_state ON inference_requests(principal_id,state,created_at);
CREATE INDEX idx_prepared_operation_state_epoch ON prepared_operations(state,runtime_epoch,updated_at);
CREATE INDEX idx_commit_state_epoch ON commit_records(state,runtime_epoch,updated_at);
CREATE INDEX idx_jobs_principal_state ON jobs(principal_id,state,created_at);
CREATE INDEX idx_job_events_job_event ON job_events(job_id,event_id);
CREATE INDEX idx_security_event_time ON security_audit_events(occurred_at,event_class);

CREATE INDEX idx_pairing_challenge_expiry ON pairing_challenges(state,expires_at_epoch_ms);
CREATE INDEX idx_access_token_registration ON access_tokens(registration_id,state,expires_at_epoch_ms);
CREATE INDEX idx_token_receipt_expiry ON token_issue_receipts(expires_at);
CREATE INDEX idx_content_report_owner_state ON content_reports(principal_id,state,updated_at);
CREATE INDEX idx_content_report_grant_expiry ON content_report_consent_grants(state,expires_at);
