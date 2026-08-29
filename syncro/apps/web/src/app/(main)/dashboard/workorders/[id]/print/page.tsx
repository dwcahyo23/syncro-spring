"use client";

import { useEffect } from "react";

import { useParams, useRouter } from "next/navigation";

import { WorkorderPrintReportPage } from "@/features/workorders/components/workorder-print-report";

/**
 * Workorder print report route (story 14-3, FR-175). Renders the WYSIWYG aggregate print
 * report for a single workorder. The report page auto-triggers window.print() on mount.
 * The .print-mode marker on <html> scopes the print stylesheet's chrome-hiding rules.
 */
export default function PrintPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();

  useEffect(() => {
    document.documentElement.classList.add("print-mode");
    return () => document.documentElement.classList.remove("print-mode");
  }, []);

  return <WorkorderPrintReportPage workOrderId={params.id} onClose={() => router.back()} />;
}
