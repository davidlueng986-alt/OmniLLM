package ai.omnillm.api;

import ai.omnillm.api.OmniErrorDetail;
@JavaDerive(toString=true)
parcelable OmniError {
  String code;
  String message;
  boolean retryable;
  OmniErrorDetail[] details;
}
