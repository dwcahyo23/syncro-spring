import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Machines">
      <ModulePlaceholder
        title="Machines"
        description="Machine management shell and future Machine Hub entry point."
        sections={["Machine list", "Manual active state", "Machine detail links"]}
      />
    </RoleGuard>
  );
}
