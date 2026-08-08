import type { APIRequestContext } from "@playwright/test";

import type { AuditLogListResponse, ListAuditLogEntriesParams } from "../../../src/lib/api/generated/model";
import { apiBaseUrl } from "../config";

// Shape of the Spring Boot 4 actuator health response restricted to the fields
// the Story 3.1 contract reads (the `mqtt` contributor under `components`).
export type ActuatorHealth = {
  status?: string;
  components?: Record<string, { status: string; details?: Record<string, string> }>;
};

export class SyncroApiClient {
  constructor(private readonly request: APIRequestContext) {}

  async getHealth() {
    return this.request.get(`${apiBaseUrl()}/health`);
  }

  // Story 3.1: the observable ingest-boundary API surface is the actuator health
  // `mqtt` contributor. The actuator base is derived by stripping /api/v1 from
  // the configured API_URL (management.endpoints.web.exposure.include: health,info).
  async getActuatorHealth() {
    const actuator = apiBaseUrl().replace(/\/api\/v1\/?$/, "");
    return this.request.get(`${actuator}/actuator/health`);
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
