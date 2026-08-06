package ai.omnillm.api;

import ai.omnillm.api.OmniChatMessage;
import ai.omnillm.api.OmniFallback;
@JavaDerive(toString=true)
parcelable OmniChatRequest {
  String requestId;
  String idempotencyKey;
  String model;
  OmniChatMessage[] messages;
  boolean stream;
  boolean hasMaxOutputTokens;
  int maxOutputTokens;
  long deadlineElapsedRealtimeNanos;
  OmniFallback fallback;
  @nullable String responseFormatSchemaId;
  @nullable String responseFormatCanonicalJson;
  @nullable String toolsSchemaId;
  @nullable String toolsCanonicalJson;
}
