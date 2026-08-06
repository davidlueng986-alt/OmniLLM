package ai.omnillm.api;

import ai.omnillm.api.OmniCommandRequest;
import ai.omnillm.api.OmniDownloadJobParameters;
import ai.omnillm.api.OmniImportJobParameters;
import ai.omnillm.api.OmniBenchmarkJobParameters;
import ai.omnillm.api.OmniDeleteJobParameters;
import ai.omnillm.api.OmniDiagnosticExportJobParameters;
import ai.omnillm.api.OmniContentReportJobParameters;
@JavaDerive(toString=true)
parcelable OmniJobSpec {
  OmniCommandRequest command;
  String jobId;
  String kind;
  @nullable OmniDownloadJobParameters download;
  @nullable OmniImportJobParameters importSpec;
  @nullable OmniBenchmarkJobParameters benchmark;
  @nullable OmniDeleteJobParameters deleteSpec;
  @nullable OmniDiagnosticExportJobParameters diagnosticExport;
  @nullable OmniContentReportJobParameters contentReport;
}
