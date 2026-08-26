/** Preventive program contract types (story 11-1, FR-130/FR-131). */

export type PreventiveCategory = "MECHANICAL" | "ELECTRICAL";
export type ScheduleType = "MONTHLY" | "ANNUAL";
export type ScheduleStatus = "SCHEDULED" | "IN_PROGRESS" | "PERFORMED" | "SKIPPED";

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
