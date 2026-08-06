package ai.omnillm.api;

import ai.omnillm.api.OmniEvent;
@JavaDerive(toString=true)
parcelable OmniEventBatch {
  long streamEpoch;
  long seqFrom;
  long seqTo;
  OmniEvent[] events;
}
