package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniSettingEntry {
  String key;
  String valueType;
  @nullable String stringValue;
  long longValue;
  double doubleValue;
  boolean boolValue;
  String[] stringListValue;
}
