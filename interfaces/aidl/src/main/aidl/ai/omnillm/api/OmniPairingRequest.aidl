package ai.omnillm.api;

import ai.omnillm.api.OmniCommandRequest;
@JavaDerive(toString=true)
parcelable OmniPairingRequest {
  OmniCommandRequest command;
  String clientDisplayName;
  String clientPublicKey;
  String[] requestedScopes;
}
