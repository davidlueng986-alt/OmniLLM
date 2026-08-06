package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniDeleteJobParameters {
  String resourceKind;
  String resourceId;
  long expectedResourceVersion;
  boolean forceAfterDrain;
}
