import type { APIRequestContext } from "@playwright/test";

export class SyncroApiClient {
  constructor(private readonly request: APIRequestContext) {}

  async getHealth() {
    return this.request.get(`${process.env.API_URL ?? "http://localhost:8080/api/v1"}/health`);
  }
}
