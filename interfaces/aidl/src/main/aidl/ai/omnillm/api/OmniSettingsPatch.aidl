package ai.omnillm.api;

import ai.omnillm.api.OmniCommandRequest;
import ai.omnillm.api.OmniSettingEntry;
@JavaDerive(toString=true)
parcelable OmniSettingsPatch {
  OmniCommandRequest command;
  OmniSettingEntry[] changes;
}
