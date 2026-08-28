/** Sparepart request contract types (story 12-1, FR-140/FR-143/FR-144; story 12-2, FR-141/FR-145). */

export type SparepartRequestType = "SPAREPART" | "CONSUMABLE" | "SERVICE_EXTERNAL";
export type SparepartRequestStatus =
  | "REQUESTED"
  | "PENDING_COMPLETION"
  | "ACKED"
  | "PROCESSING"
  | "READY"
  | "PURCHASE_REQUESTED"
  | "PART_RECEIVED"
  | "PICKED_UP"
  | "CLOSED";

export interface SparepartRequestView {
  id: string;
  requestType: SparepartRequestType;
  workOrderId: string | null;
  machineId: string | null;
  sparepartId: string | null;
  materialCode: string | null;
  quantity: number;
  estPriceId: string | null;
  estUnitPrice: string | null;
  purchaseReferenceUrl: string | null;
  status: SparepartRequestStatus;
  requestedBy: string;
  requestedAt: string;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSparepartRequestRequest {
  requestType: SparepartRequestType;
  workOrderId?: string | null;
  machineId?: string | null;
  sparepartId?: string | null;
  materialCode?: string | null;
  quantity: number;
  estPriceId?: string | null;
  estUnitPrice?: number | null;
  purchaseReferenceUrl?: string | null;
  notes?: string | null;
}

/** Story 12-2: status transition request (FR-141). */
export interface TransitionRequest {
  toStatus: SparepartRequestStatus;
  note?: string | null;
}

/** Story 12-2: manual MRE code request (FR-145). */
export interface MreRequest {
  mreCode: string;
  note?: string | null;
}
