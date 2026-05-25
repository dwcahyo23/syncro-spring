import packageJson from "../../package.json";

const currentYear = new Date().getFullYear();

export const APP_CONFIG = {
  name: "Syncro",
  version: packageJson.version,
  copyright: `© ${currentYear}, Syncro.`,
  meta: {
    title: "Syncro - Industrial Maintenance Platform",
    description:
      "Syncro connects machine setup, telemetry visibility, sparepart threshold alerts, WAHA escalation, and system health diagnostics.",
  },
};
