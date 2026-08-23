import { defineConfig } from "orval";

export default defineConfig({
  syncro: {
    // DW-117: pinned to the committed snapshot for reproducible generation.
    // Refresh it intentionally via `npm run generate:snapshot` when the backend
    // contract changes, then review the generated diff.
    input: "./openapi.json",
    output: {
      mode: "split",
      target: "src/lib/api/generated/syncro.ts",
      schemas: "src/lib/api/generated/model",
      client: "react-query",
      httpClient: "fetch",
      clean: true,
      override: {
        mutator: {
          path: "src/lib/api/orval-mutator.ts",
          name: "syncroFetch",
        },
      },
    },
  },
});
