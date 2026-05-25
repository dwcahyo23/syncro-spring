import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="System Health"
      description="SUPER_ADMIN diagnostics shell for dependencies and workers."
      sections={["Dependency cards", "Worker status", "Data quality placeholder"]}
    />
  );
}
