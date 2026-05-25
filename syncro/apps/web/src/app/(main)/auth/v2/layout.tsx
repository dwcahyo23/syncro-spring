import type { ReactNode } from "react";

export default function Layout({ children }: Readonly<{ children: ReactNode }>) {
  return <div className="flex min-h-dvh items-center justify-center bg-background">{children}</div>;
}
