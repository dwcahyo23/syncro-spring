import type { ReactNode } from "react";

import { AuthHero } from "@/features/auth/auth-hero";

export default function Layout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[1.2fr_1fr]">
      <AuthHero />
      <div className="flex items-center justify-center bg-background p-8 lg:p-12">{children}</div>
    </div>
  );
}
