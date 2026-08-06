package ai.omnillm.api;

import ai.omnillm.api.OmniSettingEntry;
@JavaDerive(toString=true)
parcelable OmniSettingsSnapshot {
  long resourceVersion;
  OmniSettingEntry[] values;
}
