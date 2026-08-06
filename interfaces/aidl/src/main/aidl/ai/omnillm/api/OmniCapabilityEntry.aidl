package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniCapabilityEntry {
  String capabilityId;
  String state;
  String evidenceLabel;
  @nullable String reasonCode;
}
