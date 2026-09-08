import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Settings"
      description="Application preference and account settings shell."
      sections={["Profile placeholder", "Theme controls remain in header", "Future auth settings"]}
    />
  );
}
