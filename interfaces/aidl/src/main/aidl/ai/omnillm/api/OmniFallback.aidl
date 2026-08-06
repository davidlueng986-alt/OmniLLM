package ai.omnillm.api;

@JavaDerive(toString=true)
parcelable OmniFallback {
  String policy;
  String[] allowedRevisionIds;
}
