package ai.omnillm.api;

import ai.omnillm.api.OmniPairingRequest;
import ai.omnillm.api.OmniPairingChallenge;
import ai.omnillm.api.OmniPairingStatus;
import ai.omnillm.api.IOmniRuntime;
interface IOmniBinding {
  OmniPairingChallenge beginPairing(in OmniPairingRequest request);
  OmniPairingStatus queryPairing(String challengeId);
  @nullable IOmniRuntime openRuntime(String registrationHandle);
  int getProtocolMajor();
  int getProtocolMinor();
}
