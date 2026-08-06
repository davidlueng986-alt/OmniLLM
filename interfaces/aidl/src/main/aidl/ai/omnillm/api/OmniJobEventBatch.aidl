package ai.omnillm.api;

import ai.omnillm.api.OmniJobEvent;
@JavaDerive(toString=true)
parcelable OmniJobEventBatch {
  String subscriptionId;
  long streamEpoch;
  long eventFrom;
  long eventTo;
  OmniJobEvent[] events;
}
