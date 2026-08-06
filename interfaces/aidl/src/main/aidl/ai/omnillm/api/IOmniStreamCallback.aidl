package ai.omnillm.api;

import ai.omnillm.api.OmniEventBatch;
import ai.omnillm.api.OmniError;
oneway interface IOmniStreamCallback {
  void onEvents(in OmniEventBatch batch);
  void onRejected(in OmniError error);
}
