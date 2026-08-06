package ai.omnillm.api;

import ai.omnillm.api.OmniCommandRequest;
@JavaDerive(toString=true)
parcelable OmniAssetUploadRequest {
  String assetId;
  OmniCommandRequest command;
  boolean hasExpectedBytes;
  long expectedBytes;
  @nullable String expectedSha256;
}
