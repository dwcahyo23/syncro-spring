"use client";

import { useSearchParams } from "next/navigation";

import { useTranslations } from "next-intl";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { InstallationManagement } from "@/features/master-data/installations/installation-management";
import { MachineGroupManagement } from "@/features/master-data/machine-groups/machine-group-management";
import { MachineManagement } from "@/features/master-data/machines/machine-management";

const TABS = ["groups", "machines", "installations"] as const;

export function MachinesTabsContent() {
  const t = useTranslations("masterData.tabs");
  const searchParams = useSearchParams();
  const tab = searchParams.get("tab");
  const defaultTab = tab && TABS.includes(tab as (typeof TABS)[number]) ? tab : "groups";

  return (
    <Tabs defaultValue={defaultTab} className="w-full">
      <TabsList className="mb-4">
        {TABS.map((value) => (
          <TabsTrigger key={value} value={value}>
            {t(value)}
          </TabsTrigger>
        ))}
      </TabsList>
      <TabsContent value="groups">
        <MachineGroupManagement />
      </TabsContent>
      <TabsContent value="machines">
        <MachineManagement />
      </TabsContent>
      <TabsContent value="installations">
        <InstallationManagement />
      </TabsContent>
    </Tabs>
  );
}
