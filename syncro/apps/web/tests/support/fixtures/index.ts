import { test as base, expect } from "@playwright/test";

import { createAuditLogEntry } from "../helpers/audit-log-factory";
import { createPlant } from "../helpers/data-factory";
import { SyncroApiClient } from "../helpers/syncro-api-client";

type SyncroFixtures = {
  api: SyncroApiClient;
  testData: {
    plant: ReturnType<typeof createPlant>;
    auditEntries: ReturnType<typeof createAuditLogEntry>[];
  };
};

export const test = base.extend<SyncroFixtures>({
  api: async ({ request }, use) => {
    await use(new SyncroApiClient(request));
  },
  testData: async ({ page: _page }, use) => {
    await use({
      plant: createPlant(),
      auditEntries: [createAuditLogEntry(), createAuditLogEntry()],
    });
  },
});

export { expect };
