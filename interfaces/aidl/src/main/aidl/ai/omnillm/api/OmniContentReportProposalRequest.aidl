package ai.omnillm.api;

import ai.omnillm.api.OmniCommandRequest;
@JavaDerive(toString=true)
parcelable OmniContentReportProposalRequest {
  OmniCommandRequest command;
  String reportId;
  String category;
  long createdAtEpochMillis;
  String appBuild;
  String modelRevisionId;
  String engineBuildId;
  String backend;
  String localPolicyVersion;
  String outputDigest;
  String userLocale;
  @nullable String description;
  @nullable String promptExcerpt;
  @nullable String outputExcerpt;
  @nullable String diagnosticSummary;
}
