import { redirect } from "next/navigation";

/** Superseded by the dashboard-shell route at /dashboard/sparepart-requests. */
export default function OldSparepartRequestsPage() {
  redirect("/dashboard/sparepart-requests");
}
