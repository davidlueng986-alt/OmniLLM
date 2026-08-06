package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniCommandRequest {
  String commandId;
  String idempotencyKey;
  boolean hasExpectedVersion;
  long expectedVersion;
  String canonicalInputDigest;
}
