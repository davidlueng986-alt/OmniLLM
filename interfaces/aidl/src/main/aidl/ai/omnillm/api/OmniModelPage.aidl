package ai.omnillm.api;

import ai.omnillm.api.OmniModelInfo;
@JavaDerive(toString=true)
parcelable OmniModelPage {
  OmniModelInfo[] items;
  @nullable String nextPageToken;
  long snapshotVersion;
}
