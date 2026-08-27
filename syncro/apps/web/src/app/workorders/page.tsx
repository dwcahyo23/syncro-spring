import { redirect } from "next/navigation";

/** Superseded by the dashboard-shell route at /dashboard/workorders. */
export default function OldWorkordersPage() {
  redirect("/dashboard/workorders");
}
