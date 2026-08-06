package ai.omnillm.api;

import ai.omnillm.api.OmniJobEventBatch;
import ai.omnillm.api.OmniError;
oneway interface IJobObserver {
  void onEvents(in OmniJobEventBatch batch);
  void onRejected(in OmniError error);
}
