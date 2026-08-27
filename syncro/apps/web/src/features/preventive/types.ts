/** Preventive program contract types (story 11-1, FR-130/FR-131). */

export type PreventiveCategory = "MECHANICAL" | "ELECTRICAL";
export type ScheduleType = "MONTHLY" | "ANNUAL";
export type ScheduleStatus = "SCHEDULED" | "IN_PROGRESS" | "PERFORMED" | "SKIPPED";
export type ChecklistStatus = "NONE" | "SUBMITTED" | "APPROVED";

export interface PreventiveProgramView {
  id: string;
  machineId: string;
  category: PreventiveCategory;
  scheduleType: ScheduleType;
  dayOfMonth: number;
  monthOfYear: number | null;
  title: string;
  description: string | null;
  active: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ShiftWindowView {
  shiftNumber: number;
  startTime: string;
  endTime: string;
}

export interface MachineShiftConfigView {
  source: string;
  inheritedFromGroup: boolean;
  shifts: ShiftWindowView[];
}

export interface PreventiveScheduleView {
  id: string;
  programId: string;
  machineId: string;
  dueDate: string;
  status: ScheduleStatus;
  derivedStatus: string;
  completedAt: string | null;
  performedBy: string | null;
  category: string;
  scheduleType: string;
  shiftConfig: MachineShiftConfigView | null;
  today: string;
  checklistStatus: ChecklistStatus;
}

export interface ChecklistItemView {
  id?: string;
  label: string;
  value: string | null;
  lsl: string | null;
  usl: string | null;
  note: string | null;
}

export interface ChecklistResultView {
  id: string;
  scheduleId: string;
  performedBy: string;
  completedAt: string;
  notes: string | null;
  leaderId: string | null;
  assessment: string | null;
  approvedAt: string | null;
  signatureObjectKey: string | null;
  signerIdentity: string | null;
  items: ChecklistItemView[];
}

export interface ChecklistView {
  status: ChecklistStatus;
  result: ChecklistResultView | null;
}

export interface PreventiveAttachmentView {
  id: string;
  scheduleId: string;
  filename: string;
  contentType: string;
  objectKey: string;
  sizeBytes: number;
  uploadedBy: string;
  createdAt: string;
  updatedAt: string | null;
  presignedUrl: string;
}

export interface SubmitChecklistRequest {
  notes?: string | null;
  items: { label: string; value?: string | null; lsl?: string | null; usl?: string | null; note?: string | null }[];
}

export interface ApproveScheduleRequest {
  signatureObjectKey: string;
  signerIdentity?: string | null;
  assessment?: string | null;
}

export interface CreatePreventiveProgramRequest {
  machineId: string;
  category: PreventiveCategory;
  scheduleType: ScheduleType;
  dayOfMonth: number;
  monthOfYear?: number | null;
  title: string;
  description?: string | null;
}
