package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniErrorDetail {
  String key;
  String valueType;
  @nullable String stringValue;
  long longValue;
  double doubleValue;
  boolean boolValue;
}
