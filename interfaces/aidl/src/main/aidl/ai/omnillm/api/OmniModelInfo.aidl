package ai.omnillm.api;

import ai.omnillm.api.OmniCapabilityEntry;
@JavaDerive(toString=true)
parcelable OmniModelInfo {
  String modelRevisionId;
  String displayName;
  String installationState;
  OmniCapabilityEntry[] capabilities;
}
