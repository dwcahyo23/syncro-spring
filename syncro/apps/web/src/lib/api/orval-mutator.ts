import { expireAuthSession, getAuthToken } from "@/lib/auth/auth-client";

export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL?.replace(/\/api\/v1$/, "") ?? "http://localhost:8080";

export class SyncroApiError extends Error {
  readonly status: number;
  readonly payload: unknown;

  constructor(status: number, payload: unknown) {
    super("Syncro API request failed.");
    this.name = "SyncroApiError";
    this.status = status;
    this.payload = payload;
  }
}

export async function syncroFetch<T>(url: string, options?: RequestInit): Promise<T> {
  const token = getAuthToken();
  const headers = new Headers(options?.headers);

  // Multipart bodies must keep the browser-set boundary; forcing application/json
  // would corrupt FormData requests generated for upload endpoints.
  const isFormData = options?.body instanceof FormData;
  if (!headers.has("Content-Type") && options?.body && !isFormData) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${url}`, {
    ...options,
    headers,
  });

  if (response.status === 204) {
    return { data: undefined, status: response.status, headers: response.headers } as T;
  }

  const payload: unknown = await response.json().catch(() => undefined);

  if (response.status === 401) {
    expireAuthSession();
    throw new SyncroApiError(response.status, payload);
  }
  if (!response.ok) {
    throw new SyncroApiError(response.status, payload);
  }

  return { data: payload, status: response.status, headers: response.headers } as T;
}
