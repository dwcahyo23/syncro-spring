import { faker } from "@faker-js/faker";

import type {
  AuditLogEntryView,
  AuditLogListResponse,
  ListAuditLogEntriesParams,
} from "../../../src/lib/api/generated/model";

const ACTIONS = ["CREATE", "UPDATE", "DELETE"] as const;
const ENTITY_TYPES = [
  "PLANT",
  "MACHINE_GROUP",
  "MACHINE",
  "SPAREPART_TAXONOMY",
  "SPAREPART",
  "INSTALLATION",
  "RESPONSIBILITY",
] as const;

export function createAuditLogEntry(overrides: Partial<AuditLogEntryView> = {}): AuditLogEntryView {
  const action = faker.helpers.arrayElement(ACTIONS);
  const entityType = faker.helpers.arrayElement(ENTITY_TYPES);
  return {
    id: faker.string.uuid(),
    actorId: faker.string.uuid(),
    actorName: faker.internet.email(),
    action,
    entityType,
    entityId: faker.string.uuid(),
    entityLabel: `${faker.string.alpha({ length: 6, casing: "upper" })}-${faker.number.int({ min: 1, max: 999 })}`,
    plantId: faker.string.uuid(),
    previousValue: action === "CREATE" ? undefined : { name: faker.commerce.productName() },
    newValue: { name: faker.commerce.productName() },
    createdAt: faker.date.recent({ days: 30 }).toISOString(),
    ...overrides,
  };
}

export function createAuditLogListResponse(overrides: Partial<AuditLogListResponse> = {}): AuditLogListResponse {
  const size = overrides.size ?? 50;
  const totalElements = overrides.totalElements ?? 0;
  const items = overrides.items ?? Array.from({ length: Math.min(totalElements, size) }, () => createAuditLogEntry());
  return {
    items,
    totalElements,
    page: overrides.page ?? 0,
    size,
    sort: overrides.sort ?? "createdAt,desc",
    ...overrides,
  };
}

export function createAuditLogParams(overrides: Partial<ListAuditLogEntriesParams> = {}): ListAuditLogEntriesParams {
  return {
    entityType: overrides.entityType ?? "MACHINE",
    actor: overrides.actor ?? "alice@syncro.dev",
    plantId: overrides.plantId,
    from: overrides.from,
    to: overrides.to,
    page: overrides.page ?? 0,
    size: overrides.size ?? 50,
    sort: overrides.sort ?? "createdAt,desc",
    ...overrides,
  };
}
