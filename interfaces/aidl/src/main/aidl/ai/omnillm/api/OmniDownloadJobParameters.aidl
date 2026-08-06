package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniDownloadJobParameters {
  String sourceUrl;
  @nullable String expectedSha256;
  boolean hasExpectedBytes;
  long expectedBytes;
  @nullable String targetName;
}
