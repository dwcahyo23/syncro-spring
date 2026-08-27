import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Departments tab). */
export default function OldDepartmentsPage() {
  redirect("/master-data/organization?tab=departments");
}
