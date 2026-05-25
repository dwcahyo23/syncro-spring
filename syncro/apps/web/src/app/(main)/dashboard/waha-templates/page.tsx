import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="WAHA Templates"
      description="WhatsApp alert message template shell."
      sections={["Template editor placeholder", "Variable picker placeholder", "Preview placeholder"]}
    />
  );
}
