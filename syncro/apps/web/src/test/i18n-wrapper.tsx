import type { ReactElement, ReactNode } from "react";

import { render } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import enMessages from "@/messages/en.json";

/**
 * Story 23-2: shared RTL wrapper for components under test. Pins locale `en`
 * and loads the real en catalog (byte-identical copy assertions keep working);
 * compose with existing providers via the `Wrapper` render option:
 *
 *   const Wrapper = ({ children }: { children: ReactNode }) => (
 *     <QueryClientProvider client={qc}>
 *       <I18nProvider>{children}</I18nProvider>
 *     </QueryClientProvider>
 *   );
 */
export function I18nProvider({ children, messages }: { readonly children: ReactNode; readonly messages?: object }) {
  return (
    <NextIntlClientProvider locale="en" messages={messages ?? enMessages}>
      {children}
    </NextIntlClientProvider>
  );
}

/**
 * Story 23-2: drop-in for testing-library `render` that composes the caller's
 * `options.wrapper` (if any) around the shared en-catalog provider.
 */
export function renderI18n(ui: ReactNode, options?: Parameters<typeof render>[1]) {
  const Inner = options?.wrapper as ((p: { children: ReactNode }) => ReactElement) | undefined;
  const wrapper = Inner
    ? ({ children }: { children: ReactNode }) => (
        <Inner>
          <I18nProvider>{children}</I18nProvider>
        </Inner>
      )
    : I18nProvider;
  return render(ui, { ...options, wrapper });
}
