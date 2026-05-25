import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Plants"
      description="Plant management shell for future create, edit, and view workflows."
      sections={["Plant list", "Create plant", "Validation states"]}
    />
  );
}
