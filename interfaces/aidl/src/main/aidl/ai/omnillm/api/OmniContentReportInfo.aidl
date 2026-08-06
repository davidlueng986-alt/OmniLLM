package ai.omnillm.api;

import ai.omnillm.api.OmniError;
@JavaDerive(toString=true)
parcelable OmniContentReportInfo {
  String reportId;
  String state;
  long expiresAtEpochMillis;
  @nullable String receiptId;
  long resourceVersion;
  @nullable OmniError error;
}
