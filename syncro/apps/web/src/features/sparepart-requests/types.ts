/** Sparepart request contract types (story 12-1, FR-140/FR-143/FR-144). */

export type SparepartRequestType = "SPAREPART" | "CONSUMABLE" | "SERVICE_EXTERNAL";
export type SparepartRequestStatus = "REQUESTED" | "PENDING_COMPLETION";

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
