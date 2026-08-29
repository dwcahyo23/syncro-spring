/**
 * Workorder print report contract types (story 14-3, FR-175). Mirror the backend
 * WorkorderPrintReportDtos exactly; kept local to the feature until the orval-generated
 * client refresh.
 */

export interface PrintReportHeader {
  id: string;
  source: string;
  status: string;
  categoryCode: string | null;
  categoryLabel: string | null;
  machineId: string;
  machineCode: string | null;
  machineName: string | null;
  plantCode: string | null;
  description: string | null;
  assignedTechnicianId: string | null;
  assignedTechnicianName: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  doneReason: string | null;
}

export interface PrintReportSession {
  id: string;
  technicianId: string;
  description: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMinutes: number | null;
}

export interface PrintReportNarrative {
  reportChronological: string | null;
  reportAnalyze: string | null;
  reportCorrective: string | null;
  reportPreventive: string | null;
}

export interface PrintReportCpk {
  cpCkLower: string | null;
  cpCkUpper: string | null;
  cpk: string | null;
  cpkPdfPresignedUrl: string | null;
  fmeaFailureType: string | null;
  stopTimeReason: string | null;
  stopTimeDetail: string | null;
}

export interface PrintReportEvidence {
  id: string;
  filename: string;
  contentType: string;
  presignedUrl: string;
}

export interface PrintReportPart {
  id: string;
  materialCode: string | null;
  quantity: number;
  status: string;
  notes: string | null;
}

export interface PrintReportSignature {
  signaturePresignedUrl: string | null;
  signerIdentity: string;
  signedBy: string;
  signedAt: string;
}

export interface WorkorderPrintReportView {
  header: PrintReportHeader;
  sessions: PrintReportSession[];
  narrative: PrintReportNarrative;
  cpk: PrintReportCpk | null;
  evidence: PrintReportEvidence[];
  parts: PrintReportPart[];
  signature: PrintReportSignature | null;
}

export interface ApproveWorkorderRequest {
  signatureObjectKey: string;
  signerIdentity?: string | null;
}

export interface WorkorderSignatureView {
  id: string;
  signatureObjectKey: string;
  signerIdentity: string;
  signedBy: string;
  signedAt: string;
}

export interface CompanyLogoView {
  objectKey: string | null;
  presignedUrl: string | null;
}
