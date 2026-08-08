import type { APIRequestContext } from "@playwright/test";

import type { AuditLogListResponse, ListAuditLogEntriesParams } from "../../../src/lib/api/generated/model";
import { apiBaseUrl } from "../config";

export class SyncroApiClient {
  constructor(private readonly request: APIRequestContext) {}

  async getHealth() {
    return this.request.get(`${apiBaseUrl()}/health`);
  }

  async listAuditLog(params: ListAuditLogEntriesParams = {}, token?: string) {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        search.set(key, String(value));
      }
    }
    const query = search.toString();
    const headers = token ? { Authorization: `Bearer ${token}` } : undefined;
    const response = await this.request.get(`${apiBaseUrl()}/audit-log${query ? `?${query}` : ""}`, { headers });
    const body = (await response.json()) as AuditLogListResponse;
    return { response, body };
  }
}
