import { PlantScopedModulePlaceholder } from "@/features/plant-scope/plant-scoped-module-placeholder";

export default function Page() {
  return (
    <PlantScopedModulePlaceholder
      title="Telemetry"
      description="Latest accepted telemetry views will render here after backend telemetry APIs exist."
      sections={["Latest machine state", "Freshness states", "Configured fields placeholder"]}
    />
  );
}
