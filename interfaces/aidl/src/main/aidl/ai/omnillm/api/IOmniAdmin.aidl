package ai.omnillm.api;

import ai.omnillm.api.OmniJobSpec;
import ai.omnillm.api.OmniJobInfo;
import ai.omnillm.api.IJobObserver;
import ai.omnillm.api.CommandResult;
import ai.omnillm.api.OmniCommandRequest;
import ai.omnillm.api.OmniSettingsPatch;
import ai.omnillm.api.OmniSettingsSnapshot;
import ai.omnillm.api.OmniAdminSnapshot;
import ai.omnillm.api.OmniConsentGrant;
import ai.omnillm.api.OmniContentReportReceipt;
import android.os.ParcelFileDescriptor;
interface IOmniAdmin {
  OmniJobInfo startJob(in OmniJobSpec spec);
  OmniJobInfo getJob(String jobId);
  CommandResult cancelJob(String jobId, in OmniCommandRequest command);
  CommandResult queryCommand(String commandId);
  String observeJobs(@nullable String cursor, int credit, in IJobObserver observer);
  void ackJobEvents(String subscriptionId, long streamEpoch, long eventToExclusive);
  void closeSubscription(String subscriptionId);
  CommandResult applySettings(in OmniSettingsPatch patch);
  OmniSettingsSnapshot getSettings();
  OmniAdminSnapshot getSnapshot();
  OmniConsentGrant reviewContentReport(String reportId, String canonicalPayloadDigest,
      String warningPolicyVersion, String localUserProfileId, in OmniCommandRequest command);
  CommandResult submitContentReport(String reportId, String consentGrantId,
      in OmniCommandRequest command);
  OmniContentReportReceipt getContentReportReceipt(String reportId);
  /**
   * LOCAL_UI playground chat (FEAT-PLAYGROUND). Runs on control plane Orchestrator.
   * Exploratory CONDITIONAL only when native attached + runtime.exploratoryExecuteEnabled;
   * never elevates engine cells to SUPPORTED. Result JSON in resultCanonicalJson.
   */
  CommandResult executePlaygroundChat(String modelRevisionId, String userMessage,
      String requestId, String idempotencyKey, in OmniCommandRequest command);
  /** Reply-loss / query path for playground request (no re-execute). */
  CommandResult queryPlaygroundRequest(String requestId);
  /** Cancel in-flight playground request. */
  CommandResult cancelPlaygroundRequest(String requestId, in OmniCommandRequest command);
  /**
   * LOCAL_UI developer-server smoke (FEAT-SERVER). Same exploratory honesty rules.
   * Result JSON in resultCanonicalJson.
   */
  CommandResult executeServerSmoke(String modelRevisionId, String requestId,
      String idempotencyKey, in OmniCommandRequest command);
  /**
   * Honest capability projection for UI negotiation (UNKNOWN / CONDITIONAL / …).
   * Never invents SUPPORTED without evidence.
   */
  String getInferenceCapabilityState(String capabilityId, String modelRevisionId);
  /**
   * LOCAL_UI local-file / SAF import: UI opens the document and passes a
   * read-only PFD + content digests. Runtime materializes via AcquisitionPipeline
   * (quarantine → verify → READY). Never elevates trust beyond LOCAL_IMPORT.
   */
  OmniJobInfo importLocalFile(in ParcelFileDescriptor contentFd, String displayName,
      String expectedSha256, long expectedBytes, String modelRevisionId,
      String artifactPackageId, String installationId, String jobId,
      in OmniCommandRequest command);
  /**
   * LOCAL_UI ModelHub load (M4). Forwards to plane.modelHubApi.startLoad.
   * Never invents QUALIFIED/SUPPORTED. Result JSON in resultCanonicalJson
   * (schema ModelLoadResult).
   */
  CommandResult loadInstalledModel(String installationId, in OmniCommandRequest command);
  /** LOCAL_UI ModelHub unload (M4). */
  CommandResult unloadInstalledModel(String installationId, in OmniCommandRequest command);
  /** LOCAL_UI pin/unpin (eviction fence only). */
  CommandResult setInstalledModelPinned(String installationId, boolean pinned,
      in OmniCommandRequest command);
  /**
   * LOCAL_UI license acceptance (M5). Append-only; does not elevate authenticity.
   */
  CommandResult acceptInstalledModelLicense(String installationId, String licenseDigest,
      String sourceAssertion, in OmniCommandRequest command);
}
