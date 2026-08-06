package ai.omnillm.api;

import android.os.ParcelFileDescriptor;

@JavaDerive(toString=true)
parcelable OmniImportJobParameters {
  String assetId;
  @nullable String expectedFormat;
  @nullable String expectedSha256;
  /** LOCAL_UI SAF import: installation target (opaque). */
  @nullable String installationId;
  /** Content-addressed revision id (64 hex) derived from package identity. */
  @nullable String modelRevisionId;
  /** Artifact package id (64 hex). */
  @nullable String artifactPackageId;
  @nullable String displayName;
  long expectedBytes;
  boolean hasExpectedBytes;
  /**
   * Read-only FD for local file bytes (UI process → runtime control plane).
   * Never a filesystem path string (INV-001 / ADR-010).
   */
  @nullable ParcelFileDescriptor contentFd;
}
