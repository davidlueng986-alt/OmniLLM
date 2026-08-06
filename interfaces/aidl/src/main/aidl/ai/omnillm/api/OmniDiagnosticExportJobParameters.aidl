package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniDiagnosticExportJobParameters {
  boolean includeDetail;
  String[] categories;
  long ttlSeconds;
}
