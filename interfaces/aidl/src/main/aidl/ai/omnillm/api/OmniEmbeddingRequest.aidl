package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniEmbeddingRequest {
  String requestId;
  String idempotencyKey;
  String model;
  String[] inputs;
  @nullable String encodingFormat;
  boolean hasDimensions;
  int dimensions;
  long deadlineElapsedRealtimeNanos;
}
