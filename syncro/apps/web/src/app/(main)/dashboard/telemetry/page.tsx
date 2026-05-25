import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Telemetry"
      description="Latest accepted telemetry views will render here after backend telemetry APIs exist."
      sections={["Latest machine state", "Freshness states", "Configured fields placeholder"]}
    />
  );
}
