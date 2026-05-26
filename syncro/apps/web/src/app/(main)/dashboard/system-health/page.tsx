import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN"]} title="System Health">
      <ModulePlaceholder
        title="System Health"
        description="SUPER_ADMIN diagnostics shell for dependencies and workers."
        sections={["Dependency cards", "Worker status", "Data quality placeholder"]}
      />
    </RoleGuard>
  );
}
