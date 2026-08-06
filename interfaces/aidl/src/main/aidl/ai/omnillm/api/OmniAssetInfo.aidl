package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable OmniAssetInfo {
  String assetId;
  String state;
  long bytes;
  @nullable String sha256;
  long expiresAtEpochMillis;
  long resourceVersion;
  @nullable OmniError error;
}
