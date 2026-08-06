package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniBenchmarkJobParameters {
  String modelRevisionId;
  String engineBuildId;
  String backend;
  String measurementProfileId;
  int iterations;
}
