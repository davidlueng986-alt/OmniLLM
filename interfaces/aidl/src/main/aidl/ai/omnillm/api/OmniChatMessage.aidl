package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniChatMessage {
  String role;
  String content;
  String[] assetIds;
}
