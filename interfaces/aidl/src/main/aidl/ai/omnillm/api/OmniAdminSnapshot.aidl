package ai.omnillm.api;

import ai.omnillm.api.OmniJobInfo;
import ai.omnillm.api.OmniModelInfo;
import ai.omnillm.api.OmniSettingsSnapshot;
@JavaDerive(toString=true)
parcelable OmniAdminSnapshot {
  long snapshotVersion;
  String runtimeState;
  String lanState;
  OmniJobInfo[] activeJobs;
  OmniModelInfo[] models;
  OmniSettingsSnapshot settings;
}
