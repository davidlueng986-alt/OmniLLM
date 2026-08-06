package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniContentReportReceipt {
  String receiptId;
  String reportId;
  long acceptedAtEpochMillis;
  String statusUrl;
}
