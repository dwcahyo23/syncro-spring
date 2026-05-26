import { PlantScopedModulePlaceholder } from "@/features/plant-scope/plant-scoped-module-placeholder";

export default function Page() {
  return (
    <PlantScopedModulePlaceholder
      title="Alerts"
      description="Alert lists and threshold evidence views will render here after alert APIs exist."
      sections={["Open alerts", "Acknowledged alerts", "Resolved history"]}
    />
  );
}
