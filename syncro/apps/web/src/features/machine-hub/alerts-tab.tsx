"use client";

import { EmptyState } from "@/components/ui/empty-state";

export interface AlertsTabProps {
  machineCode: string;
}

export function AlertsTab({ machineCode: _machineCode }: AlertsTabProps) {
  return (
    <EmptyState
      title="No alerts yet"
      description="Alert management arrives in Epic 4. This machine has no active alerts."
    />
  );
}
