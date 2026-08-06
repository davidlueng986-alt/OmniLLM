package ai.omnillm.api;

import ai.omnillm.api.OmniCommandRequest;
@JavaDerive(toString=true)
parcelable OmniAssetCreateRequest {
  String assetId;
  OmniCommandRequest command;
  String purpose;
  long maxBytes;
  @nullable String contentTypeHint;
  @nullable String expectedSha256;
  long ttlSeconds;
}
