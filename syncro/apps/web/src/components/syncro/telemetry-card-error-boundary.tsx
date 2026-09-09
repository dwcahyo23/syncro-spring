"use client";

import { Component, type ReactNode } from "react";

import { useTranslations } from "next-intl";

type TelemetryCardErrorBoundaryProps = {
  machineCode?: string;
  children: ReactNode;
  onRetry?: () => void;
};

type TelemetryCardErrorBoundaryState = {
  hasError: boolean;
};

/**
 * Class boundary cannot call hooks, so the translated fallback strings arrive as
 * props from the function wrapper exported under the public name.
 */
class Boundary extends Component<
  TelemetryCardErrorBoundaryProps & { readonly messages: { title: string; body: string; retry: string } },
  TelemetryCardErrorBoundaryState
> {
  state: TelemetryCardErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): TelemetryCardErrorBoundaryState {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return (
        <div
          className="flex h-full min-h-52 flex-col items-center justify-center gap-2 rounded-xl border border-destructive/30 p-4 text-center"
          role="alert"
        >
          <p className="font-medium text-sm">{this.props.messages.title}</p>
          <p className="text-muted-foreground text-xs">{this.props.messages.body}</p>
          <button
            className="rounded-lg border px-2.5 py-1 text-xs hover:bg-muted"
            onClick={() => {
              this.setState({ hasError: false });
              this.props.onRetry?.();
            }}
            type="button"
          >
            {this.props.messages.retry}
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

export function TelemetryCardErrorBoundary(props: TelemetryCardErrorBoundaryProps) {
  const t = useTranslations("common");
  const body = props.machineCode
    ? t("telemetryDisplayFailedFor", { machineCode: props.machineCode })
    : t("telemetryDisplayFailedPlain");
  return <Boundary {...props} messages={{ title: t("telemetryDisplayFailed"), body, retry: t("retry") }} />;
}
