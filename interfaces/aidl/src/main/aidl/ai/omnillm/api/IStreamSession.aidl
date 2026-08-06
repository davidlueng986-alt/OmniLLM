package ai.omnillm.api;

import ai.omnillm.api.CommandResult;
import ai.omnillm.api.OmniCommandRequest;
import ai.omnillm.api.OmniRequestState;
interface IStreamSession {
  void ackEvents(long streamEpoch, long seqToExclusive);
  CommandResult cancel(in OmniCommandRequest command);
  OmniRequestState query();
  void close();
}
