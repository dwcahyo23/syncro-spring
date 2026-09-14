"use client";

import { useParams } from "next/navigation";

import { SparepartRequestDetailContent } from "@/features/sparepart-requests/components/sparepart-request-detail";

/**
 * Sparepart-request detail route — the warehouse/inventory cockpit where request
 * status is processed. The workorder page stays read-only by domain boundary
 * (leader decision 2026-09-14).
 */
export default function SparepartRequestDetailPage() {
  const params = useParams<{ id: string }>();
  return <SparepartRequestDetailContent requestId={params.id} />;
}
