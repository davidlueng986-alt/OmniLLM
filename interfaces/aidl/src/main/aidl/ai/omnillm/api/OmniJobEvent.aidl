package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable OmniJobEvent {
  String eventId;
  String jobId;
  int attemptNo;
  String kind;
  String state;
  double progress;
  long occurredAtEpochMillis;
  @nullable OmniError error;
}
