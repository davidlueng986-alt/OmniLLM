package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable OmniRequestState {
  String requestId;
  String state;
  long resourceVersion;
  @nullable String actualModelRevisionId;
  @nullable String engineBuildId;
  @nullable String backend;
  @nullable OmniError terminalError;
}
