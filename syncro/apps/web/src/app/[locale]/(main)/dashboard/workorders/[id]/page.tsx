"use client";

import { useParams } from "next/navigation";

import { WorkorderDetailPageContent } from "@/features/workorders/components/workorder-detail";

/**
 * Workorder detail route (QA 2026-09-14, finding #4): header, technicians, work-session
 * timeline, and linked sparepart requests for one workorder. Composed from existing
 * endpoints — the backend exposes no GET /{id} aggregate.
 */
export default function WorkorderDetailPage() {
  const params = useParams<{ id: string }>();
  return <WorkorderDetailPageContent workOrderId={params.id} />;
}
