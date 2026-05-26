import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { RoleGuard } from "@/components/syncro/role-guard";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN"]} title="WAHA Templates">
      <ModulePlaceholder
        title="WAHA Templates"
        description="WhatsApp alert message template shell."
        sections={["Template editor placeholder", "Variable picker placeholder", "Preview placeholder"]}
      />
    </RoleGuard>
  );
}
