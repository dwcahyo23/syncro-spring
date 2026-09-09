import { SyncroApiError } from "./orval-mutator";

/**
 * Story 23-2: single home for the `errorResponse()` shape check that was
 * duplicated across 14 management screens, plus the code→message resolver
 * (`apiErrorMessage`) so every API error toast renders translated copy from
 * the `errors` catalog instead of a hardcoded English string.
 */
export type ApiErrorResponse = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

/** Narrow an unknown thrown value to the Syncro API error envelope, or null. */
export function errorResponse(error: unknown): ApiErrorResponse | null {
  if (!(error instanceof SyncroApiError) || !error.payload || typeof error.payload !== "object") {
    return null;
  }
  const payload = error.payload as ApiErrorResponse;
  return typeof payload.code === "string" && typeof payload.message === "string" ? payload : null;
}

/** Structural slice of a next-intl translator needed by {@link apiErrorMessage}. */
export type ErrorTranslator = {
  (key: string, values?: Record<string, string | number | Date>): string;
  has(key: string): boolean;
};

/**
 * Resolve the display message for an API error payload (AD-23: the backend
 * `code` is the contract; the message is display copy).
 *
 * Chain: `errors.<CODE>` when the catalog has it → backend `message` (English
 * data, rendered as-is) → `errors.generic`. Never renders a raw code, never
 * crashes. Call sites whose code means something action-specific (e.g. the
 * same `INVALID_STATE_TRANSITION` on approve vs. MRE) branch on the code
 * FIRST and render their own translated string, falling back to this chain.
 */
export function apiErrorMessage(t: ErrorTranslator, response: ApiErrorResponse | null): string {
  if (response && t.has(response.code)) {
    return t(response.code);
  }
  return response?.message || t("generic");
}
