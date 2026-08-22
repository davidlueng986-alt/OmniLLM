package ai.omnillm.api;

import ai.omnillm.api.OmniCapabilityEntry;
@JavaDerive(toString=true)
parcelable OmniModelInfo {
  String modelRevisionId;
  String displayName;
  String installationState;
  OmniCapabilityEntry[] capabilities;
  @nullable String installationId;
  @nullable String artifactPackageId;
  @nullable String licenseStatus;
  @nullable String licenseDigest;
  boolean pinned;
  @nullable String loadedModelState;
  String[] allowedActions;
  int liveReferenceCount;
  @nullable String compatibilityStatus;
  boolean hasAuthenticityOk;
  boolean authenticityOk;
  @nullable String acquisitionChannel;
  boolean hasResourceVersion;
  long resourceVersion;
}
