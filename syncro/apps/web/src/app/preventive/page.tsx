import { redirect } from "next/navigation";

/** Superseded by the dashboard-shell route at /dashboard/preventive. */
export default function OldPreventivePage() {
  redirect("/dashboard/preventive");
}
