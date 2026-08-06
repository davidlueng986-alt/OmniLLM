package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable OmniEvent {
  String kind;
  @nullable String textDelta;
  boolean hasTokenCount;
  long tokenCount;
  boolean hasProgress;
  double progress;
  @nullable String actualModelRevisionId;
  @nullable String engineBuildId;
  @nullable String backend;
  @nullable String terminalState;
  @nullable OmniError error;
  @nullable String extensionSchemaId;
  @nullable String extensionCanonicalJson;
  boolean terminal;
}
