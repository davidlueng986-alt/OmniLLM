package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable OmniPairingStatus {
  String challengeId;
  String state;
  @nullable String registrationHandle;
  String[] grantedScopes;
  long expiresAtEpochMillis;
  @nullable OmniError error;
}
