import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Users tab). */
export default function OldUsersPage() {
  redirect("/master-data/organization?tab=users");
}
