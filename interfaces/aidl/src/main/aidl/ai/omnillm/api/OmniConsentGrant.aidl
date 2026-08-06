package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniConsentGrant {
  String grantId;
  String principalId;
  String reportId;
  String canonicalPayloadDigest;
  String warningPolicyVersion;
  String localUserProfileId;
  long issuedAtEpochMillis;
  long expiresAtEpochMillis;
  String nonce;
}
