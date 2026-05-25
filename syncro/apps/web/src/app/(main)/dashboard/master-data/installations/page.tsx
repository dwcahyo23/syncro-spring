import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Installations"
      description="Machine sparepart installation shell for lifetime baseline setup."
      sections={["Baseline counter", "Expected count", "Threshold percent"]}
    />
  );
}
