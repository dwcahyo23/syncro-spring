import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Spareparts"
      description="Sparepart master data shell for taxonomy-backed spareparts."
      sections={["Taxonomy references", "Sparepart list", "Dense table placeholder"]}
    />
  );
}
