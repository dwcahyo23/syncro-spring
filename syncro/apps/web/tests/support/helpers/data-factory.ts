import { faker } from "@faker-js/faker";

export type PlantTestData = {
  code: string;
  name: string;
  timezone: string;
  isActive: boolean;
};

export function createPlant(overrides: Partial<PlantTestData> = {}): PlantTestData {
  return {
    code: `PLT-${faker.string.alphanumeric({ length: 6, casing: "upper" })}`,
    name: `${faker.location.city()} Plant`,
    timezone: "Asia/Jakarta",
    isActive: true,
    ...overrides,
  };
}
