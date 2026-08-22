package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable OmniJobInfo {
  String jobId;
  String state;
  long resourceVersion;
  double progress;
  @nullable OmniError error;
  String kind;
  @nullable String canonicalSpecDigest;
}
