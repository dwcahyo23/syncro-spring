import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default function Page() {
  return (
    <ModulePlaceholder
      title="Machines"
      description="Machine management shell and future Machine Hub entry point."
      sections={["Machine list", "Manual active state", "Machine detail links"]}
    />
  );
}
