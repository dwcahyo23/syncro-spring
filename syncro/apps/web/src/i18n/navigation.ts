import { createNavigation } from "next-intl/navigation";

import { routing } from "./routing";

// Locale-aware wrappers: hrefs render under the active locale and
// usePathname returns the locale-stripped path for active-item matching.
export const { Link, redirect, usePathname, useRouter, getPathname } = createNavigation(routing);
