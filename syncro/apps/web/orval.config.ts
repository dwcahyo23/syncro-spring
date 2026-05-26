import { defineConfig } from "orval";

export default defineConfig({
  syncro: {
    input: "http://localhost:8080/v3/api-docs",
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
