import { PlantScopedModulePlaceholder } from "@/features/plant-scope/plant-scoped-module-placeholder";

export default function Page() {
  return (
    <PlantScopedModulePlaceholder
      title="Operations Overview"
      description="What needs attention now across machines, alerts, telemetry, and platform health."
      sections={["Open alert summary", "Latest telemetry shell", "Health summary placeholder"]}
    />
  );
}
