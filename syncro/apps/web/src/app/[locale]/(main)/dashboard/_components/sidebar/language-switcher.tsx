"use client";

import { Languages } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { usePathname, useRouter } from "@/i18n/navigation";
import { isLocale, routing } from "@/i18n/routing";
import { NEXT_LOCALE } from "@/i18n/switch-locale";
import { setClientCookie } from "@/lib/cookie.client";

/**
 * Story 23-3: ID/EN switcher in the header-right cluster. Navigation stays
 * URL-owned — the i18n router swaps the locale segment client-side (path and
 * query preserved); the NEXT_LOCALE cookie only feeds the bare-root redirect
 * in src/proxy.ts.
 */
export function LanguageSwitcher() {
  const t = useTranslations("navigation.languageSwitcher");
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();

  const switchTo = (value: string) => {
    if (!isLocale(value) || value === locale) return;
    // usePathname() drops query and hash, so rebuild the full href from the
    // live URL at click time (also avoids SSR/prerender surprises). Embedding
    // the raw search string preserves multi-value params verbatim; when
    // search and hash are empty this is the plain pathname shortcut.
    const { search, hash } = window.location;
    router.push(`${pathname}${search}${hash}`, { locale: value });
    // routing.ts disables next-intl's own cookie sync (localeCookie: false),
    // so this is the cookie's only writer and the chosen locale survives
    // later hard visits to pages of the other locale.
    setClientCookie(NEXT_LOCALE, value, 365);
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="icon" aria-label={t("switchAria", { current: t(`locales.${locale}`) })}>
          <Languages />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>{t("label")}</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={locale} onValueChange={switchTo}>
          {routing.locales.map((target) => (
            <DropdownMenuRadioItem key={target} value={target}>
              {t(`locales.${target}`)}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
