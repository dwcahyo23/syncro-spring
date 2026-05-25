import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Operations Overview"
      description="What needs attention now across machines, alerts, telemetry, and platform health."
      sections={["Open alert summary", "Latest telemetry shell", "Health summary placeholder"]}
    />
  );
}
