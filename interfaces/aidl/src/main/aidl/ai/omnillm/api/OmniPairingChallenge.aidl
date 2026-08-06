package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniPairingChallenge {
  String challengeId;
  String observedPrincipalSummary;
  String[] requestedScopes;
  String state;
  long expiresAtEpochMillis;
}
