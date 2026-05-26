import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Responsibility">
      <ModulePlaceholder
        title="Responsibility"
        description="Machine-specific responsibility assignment shell."
        sections={["TECHNICIAN", "STAFF", "LEADER", "SPV", "MANAGER"]}
      />
    </RoleGuard>
  );
}
