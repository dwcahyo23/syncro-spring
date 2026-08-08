package com.syncro.telemetry.application;

class RejectingTelemetryValidationService extends TelemetryValidationService {

  RejectingTelemetryValidationService() {
    super(null, null);
  }

  @Override
  public Result validate(String topic, String payload) {
    return new Result.Rejected("unknown_machine", null);
  }
}
