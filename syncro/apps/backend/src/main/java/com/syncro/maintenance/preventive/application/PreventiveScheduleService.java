package com.syncro.maintenance.preventive.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRow;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.shiftconfig.application.ShiftConfigService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preventive calendar read (FR-131, AD-12, story 11-1): scope-filtered schedules with
 * a server-derived status (OVERDUE when SCHEDULED and due before today) and the
 * machine's shift configuration surfaced as calendar context. OVERDUE is never stored.
 */
@Service
public class PreventiveScheduleService {

  private final PreventiveScheduleRepository schedules;
  private final ShiftConfigService shiftConfig;
  private final OperationalScopeService scopes;
  private final Clock clock;
  private final PreventiveChecklistService checklists;

  public PreventiveScheduleService(PreventiveScheduleRepository schedules, ShiftConfigService shiftConfig,
      OperationalScopeService scopes, Clock clock, PreventiveChecklistService checklists) {
    this.schedules = schedules;
    this.shiftConfig = shiftConfig;
    this.scopes = scopes;
    this.clock = clock;
    this.checklists = checklists;
  }

  @Transactional(readOnly = true)
  public List<ScheduleView> list(AuthenticatedUser user) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = new HashSet<UUID>();
    groupIds.addAll(scope.machineGroupIds());
    groupIds.addAll(scope.activeTeamIds());

    var rows = schedules.findScopedSchedules(
        unrestricted, scope.plantIds() == null ? List.of() : scope.plantIds(), groupIds);
    var today = LocalDate.now(clock);
    var result = new ArrayList<ScheduleView>();
    for (var row : rows) {
      var schedule = row.schedule();
      var derived = schedule.getStatus() == ScheduleStatus.SCHEDULED && schedule.getDueDate().isBefore(today)
          ? "OVERDUE" : schedule.getStatus().name();
      var checklistStatus = checklists.statusFor(schedule.getId()).name();
      result.add(new ScheduleView(schedule.getId(), schedule.getProgramId(), schedule.getMachineId(),
          schedule.getDueDate(), schedule.getStatus().name(), derived, schedule.getCompletedAt(),
          schedule.getPerformedBy(), row.category().name(), row.scheduleType().name(),
          shiftConfig.resolveByMachineId(row.machineId()), today, checklistStatus));
    }
    return result;
  }

  /** Schedule read view: stored status + server-derived status + shift calendar context. */
  public record ScheduleView(UUID id, UUID programId, UUID machineId, LocalDate dueDate, String status,
      String derivedStatus, java.time.Instant completedAt, UUID performedBy, String category, String scheduleType,
      com.syncro.shiftconfig.application.ShiftConfigService.MachineShiftConfigView shiftConfig, LocalDate today,
      String checklistStatus) {
  }
}