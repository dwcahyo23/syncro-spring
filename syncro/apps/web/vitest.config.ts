import { defineConfig } from "vitest/config";

import path from "node:path";

export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    // Story 23-2: heavy jsdom files (full-form renders + en catalog) blow the 5s
    // default under 41-file parallel load; raise it so CI is not timing-flaky.
    testTimeout: 20_000,
    include: ["src/**/*.test.{ts,tsx}", "tests/support/**/*.test.{ts,tsx}"],
    setupFiles: ["./vitest.setup.ts"],
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
});
