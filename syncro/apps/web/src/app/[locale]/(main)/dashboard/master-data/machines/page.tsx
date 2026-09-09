import { Suspense } from "react";

import { getTranslations } from "next-intl/server";

import { RoleGuard } from "@/components/syncro/role-guard";

import { MachinesTabsContent } from "./tabs-content";

export default async function Page() {
  const t = await getTranslations("machineHub");
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title={t("page.title")}>
      <div className="space-y-6">
        <Suspense fallback={<div className="p-4 text-muted-foreground">{t("loadingMachines")}</div>}>
          <MachinesTabsContent />
        </Suspense>
      </div>
    </RoleGuard>
  );
}
