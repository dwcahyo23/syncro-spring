import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";

import { AUTH_TOKEN_COOKIE } from "@/lib/auth/auth-session";

const protectedRoutes = [
  "/dashboard",
  "/operations-overview",
  "/telemetry",
  "/alerts",
  "/master-data",
  "/waha-templates",
  "/audit-log",
  "/system-health",
  "/settings",
];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isProtectedRoute = protectedRoutes.some((route) => pathname === route || pathname.startsWith(`${route}/`));
  if (!isProtectedRoute) {
    return NextResponse.next();
  }

  const token = request.cookies.get(AUTH_TOKEN_COOKIE)?.value;
  if (token) {
    return NextResponse.next();
  }

  const loginUrl = request.nextUrl.clone();
  loginUrl.pathname = "/auth/v2/login";
  loginUrl.searchParams.set("next", pathname);
  return NextResponse.redirect(loginUrl);
}

export const config = {
  matcher: [
    "/dashboard/:path*",
    "/operations-overview/:path*",
    "/telemetry/:path*",
    "/alerts/:path*",
    "/master-data/:path*",
    "/waha-templates/:path*",
    "/audit-log/:path*",
    "/system-health/:path*",
    "/settings/:path*",
  ],
};
