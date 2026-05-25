import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Responsibility"
      description="Machine-specific responsibility assignment shell."
      sections={["TECHNICIAN", "STAFF", "LEADER", "SPV", "MANAGER"]}
    />
  );
}
