import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";

import createMiddleware from "next-intl/middleware";

import { AUTH_TOKEN_COOKIE } from "@/lib/auth/auth-session";

import { isLocale, routing, stripLocale } from "./i18n/routing";

const handleI18nRouting = createMiddleware(routing);

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

export default function proxy(request: NextRequest) {
  // next-intl runs first so the locale is negotiated and applied to the URL
  // before the auth guard evaluates the path.
  const response = handleI18nRouting(request);

  // Locale redirects (e.g. `/alerts` -> `/id/alerts`) short-circuit here; the
  // guard re-evaluates on the redirected request, preserving the intl response
  // (it carries the NEXT_LOCALE cookie).
  if (response.status >= 300 && response.status < 400) {
    return response;
  }

  const { pathname } = request.nextUrl;
  // The guard compares the locale-stripped path so `/id/alerts` and `/alerts`
  // behave identically.
  const barePathname = stripLocale(pathname);
  const isProtectedRoute = protectedRoutes.some(
    (route) => barePathname === route || barePathname.startsWith(`${route}/`),
  );
  if (!isProtectedRoute) {
    return response;
  }

  const token = request.cookies.get(AUTH_TOKEN_COOKIE)?.value;
  if (token) {
    return response;
  }

  const [, prefix] = pathname.split("/");
  const locale = isLocale(prefix) ? prefix : routing.defaultLocale;
  const loginUrl = request.nextUrl.clone();
  loginUrl.pathname = `/${locale}/auth/v2/login`;
  // Keep the full prefixed path so the post-login return lands in the right locale.
  loginUrl.searchParams.set("next", pathname);
  return NextResponse.redirect(loginUrl);
}

export const config = {
  matcher: [
    // Match all pathnames except for
    // - … if they start with `/api`, `/_next` or `/_vercel`
    // - … the ones containing a dot (e.g. `favicon.ico`)
    "/((?!api|trpc|_next|_vercel|.*\\..*).*)",
    // The generic match excludes dotted paths (external workorder sheet numbers
    // and machine codes), so cover those trees explicitly — unprefixed (legacy
    // URLs) and locale-prefixed. ponytail: `:locale` is unconstrained on
    // purpose; next-intl 404s unknown prefixes before the guard matters.
    "/dashboard/:path*",
    "/master-data/:path*",
    "/operations-overview/:path*",
    "/alerts/:path*",
    "/telemetry/:path*",
    "/:locale/dashboard/:path*",
    "/:locale/master-data/:path*",
    "/:locale/operations-overview/:path*",
    "/:locale/alerts/:path*",
    "/:locale/telemetry/:path*",
  ],
};
