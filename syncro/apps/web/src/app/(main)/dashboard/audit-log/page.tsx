import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Audit Log"
      description="Immutable master data change history shell."
      sections={["Entity filters", "Actor filters", "Before/after detail"]}
    />
  );
}
