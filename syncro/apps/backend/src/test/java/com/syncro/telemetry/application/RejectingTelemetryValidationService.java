package com.syncro.telemetry.application;

class RejectingTelemetryValidationService extends TelemetryValidationService {

  RejectingTelemetryValidationService() {
    this("unknown_machine");
  }

  RejectingTelemetryValidationService(String reason) {
    super(null, null);
    this.reason = reason;
  }

  private final String reason;

  @Override
  public Result validate(String topic, String payload) {
    return new Result.Rejected(reason, null);
  }
}
