package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable CommandResult {
  String commandId;
  String state;
  long resourceVersion;
  @nullable String affectedResourceId;
  @nullable String resultSchemaId;
  @nullable String resultCanonicalJson;
  @nullable OmniError error;
}
