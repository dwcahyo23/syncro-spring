import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Alerts"
      description="Alert lists and threshold evidence views will render here after alert APIs exist."
      sections={["Open alerts", "Acknowledged alerts", "Resolved history"]}
    />
  );
}
