import { Component, type ReactNode } from "react";

type TelemetryCardErrorBoundaryProps = {
  machineCode?: string;
  children: ReactNode;
  onRetry?: () => void;
};

type TelemetryCardErrorBoundaryState = {
  hasError: boolean;
};

export class TelemetryCardErrorBoundary extends Component<
  TelemetryCardErrorBoundaryProps,
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
          <p className="font-medium text-sm">Telemetry display failed</p>
          <p className="text-muted-foreground text-xs">
            Could not render telemetry{this.props.machineCode ? ` for ${this.props.machineCode}` : ""}. Other machines
            are unaffected.
          </p>
          <button
            className="rounded-lg border px-2.5 py-1 text-xs hover:bg-muted"
            onClick={() => {
              this.setState({ hasError: false });
              this.props.onRetry?.();
            }}
            type="button"
          >
            Retry
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
