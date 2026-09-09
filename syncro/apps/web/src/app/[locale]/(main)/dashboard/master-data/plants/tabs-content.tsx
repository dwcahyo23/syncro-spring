"use client";

import { useSearchParams } from "next/navigation";

import { useTranslations } from "next-intl";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { PlantManagement } from "@/features/master-data/plants/plant-management";
import { SetupCompletenessPage } from "@/features/setup/setup-completeness-page";

const TABS = ["plants", "setup"] as const;

export function PlantsTabsContent() {
  const t = useTranslations("masterData.tabs");
  const searchParams = useSearchParams();
  const tab = searchParams.get("tab");
  const defaultTab = tab && TABS.includes(tab as (typeof TABS)[number]) ? tab : "plants";

  return (
    <Tabs defaultValue={defaultTab} className="w-full">
      <TabsList className="mb-4">
        {TABS.map((value) => (
          <TabsTrigger key={value} value={value}>
            {t(value)}
          </TabsTrigger>
        ))}
      </TabsList>
      <TabsContent value="plants">
        <PlantManagement />
      </TabsContent>
      <TabsContent value="setup">
        <SetupCompletenessPage />
      </TabsContent>
    </Tabs>
  );
}
