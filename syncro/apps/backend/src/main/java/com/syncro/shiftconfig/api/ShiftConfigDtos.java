package com.syncro.shiftconfig.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalTime;
import java.util.List;

public final class ShiftConfigDtos {
  private ShiftConfigDtos() {
  }

  public record ShiftWindowRequest(
      @JsonFormat(pattern = "HH:mm") LocalTime startTime,
      @JsonFormat(pattern = "HH:mm") LocalTime endTime) {
  }

  public record SetShiftConfigRequest(List<ShiftWindowRequest> shifts) {
  }

  public record ShiftWindowView(int shiftNumber, String startTime, String endTime) {
  }

  public record MachineGroupShiftConfigView(List<ShiftWindowView> shifts) {
  }

  public record MachineShiftConfigView(String source, boolean inheritedFromGroup, List<ShiftWindowView> shifts) {
  }
}