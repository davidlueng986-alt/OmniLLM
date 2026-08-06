package ai.omnillm.api;

import android.os.ParcelFileDescriptor;
import ai.omnillm.api.OmniChatRequest;
import ai.omnillm.api.OmniEmbeddingRequest;
import ai.omnillm.api.IOmniStreamCallback;
import ai.omnillm.api.IStreamSession;
import ai.omnillm.api.OmniRequestState;
import ai.omnillm.api.OmniModelPage;
import ai.omnillm.api.CommandResult;
import ai.omnillm.api.OmniCommandRequest;
import ai.omnillm.api.OmniAssetCreateRequest;
import ai.omnillm.api.OmniAssetInfo;
import ai.omnillm.api.OmniAssetUploadRequest;
import ai.omnillm.api.OmniContentReportProposalRequest;
import ai.omnillm.api.OmniContentReportInfo;
import ai.omnillm.api.OmniContentReportReceipt;
interface IOmniRuntime {
  IStreamSession chat(in OmniChatRequest request, in IOmniStreamCallback callback);
  IStreamSession embed(in OmniEmbeddingRequest request, in IOmniStreamCallback callback);
  OmniRequestState queryRequest(String requestId);
  CommandResult cancelRequest(String requestId, in OmniCommandRequest command);
  CommandResult queryCommand(String commandId);
  OmniModelPage listModels(@nullable String pageToken, int pageSize);
  OmniAssetInfo createAsset(in OmniAssetCreateRequest request);
  CommandResult uploadAssetContent(in OmniAssetUploadRequest request,
      in ParcelFileDescriptor content);
  OmniAssetInfo commitAsset(String assetId, in OmniCommandRequest command);
  OmniAssetInfo getAsset(String assetId);
  CommandResult deleteAsset(String assetId, in OmniCommandRequest command);
  OmniContentReportInfo createContentReportProposal(in OmniContentReportProposalRequest request);
  OmniContentReportInfo getContentReport(String reportId);
  OmniContentReportReceipt getContentReportReceipt(String reportId);
  CommandResult cancelContentReport(String reportId, in OmniCommandRequest command);
  CommandResult discardContentReport(String reportId, in OmniCommandRequest command);
  int getProtocolMajor();
  int getProtocolMinor();
}
