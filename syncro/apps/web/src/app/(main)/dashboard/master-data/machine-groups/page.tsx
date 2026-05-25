import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Machine Groups"
      description="Plant-scoped process line management shell."
      sections={["Plant filter", "Machine group list", "Duplicate-name validation"]}
    />
  );
}
