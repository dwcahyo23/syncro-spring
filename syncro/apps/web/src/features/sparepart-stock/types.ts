/** Sparepart stock contract types (story 12-4, FR-146). */

export interface SparepartStockView {
  materialCode: string;
  plantId: string;
  stockOnHand: string;
  orderPoint: string;
  orderQty: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  reorderWarning: boolean;
}

export interface SparepartStockListView {
  items: SparepartStockView[];
}

/** Story 12-4: create (upsert) a stock row. */
export interface CreateSparepartStockRequest {
  materialCode: string;
  plantId: string;
  stockOnHand: number;
  orderPoint: number;
  orderQty: number;
}

/** Story 12-4: partial overwrite with optimistic lock (at least one value field required). */
export interface UpdateSparepartStockRequest {
  plantId: string;
  version: number;
  stockOnHand?: number | null;
  orderPoint?: number | null;
  orderQty?: number | null;
}

/** Story 12-4: signed delta adjustment. */
export interface AdjustSparepartStockRequest {
  plantId: string;
  delta: number;
}
