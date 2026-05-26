import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Installations">
      <ModulePlaceholder
        title="Installations"
        description="Machine sparepart installation shell for lifetime baseline setup."
        sections={["Baseline counter", "Expected count", "Threshold percent"]}
      />
    </RoleGuard>
  );
}
