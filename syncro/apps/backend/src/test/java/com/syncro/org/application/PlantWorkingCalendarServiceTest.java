package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.org.application.PlantWorkingCalendarService.DuplicateCalendarException;
import com.syncro.org.application.PlantWorkingCalendarService.PlantNotFoundForCalendarException;
import com.syncro.org.domain.WorkweekMode;
import com.syncro.org.infrastructure.db.PlantWorkingCalendarDateRepository;
import com.syncro.org.infrastructure.db.PlantWorkingCalendarEntity;
import com.syncro.org.infrastructure.db.PlantWorkingCalendarRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlantWorkingCalendarServiceTest {

  @Mock private PlantWorkingCalendarRepository calendars;
  @Mock private PlantWorkingCalendarDateRepository dates;
  @Mock private PlantRepository plants;
  @Mock private PlantScopeService plantScopes;
  @Mock private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

  private PlantWorkingCalendarService service() {
    return new PlantWorkingCalendarService(calendars, dates, plants, plantScopes, auditLog, clock);
  }

  @Test
  void createPersistsCalendarWithAudit() {
    var plantId = UUID.randomUUID();
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "GM1", "Plant GM1",
        Instant.now(clock), Instant.now(clock));
    when(plants.existsById(plantId)).thenReturn(true);
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(calendars.findByPlantIdAndYear(plantId, 2026)).thenReturn(Optional.empty());
    when(calendars.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service().create(manager(), new PlantWorkingCalendarService.CreateCalendarCommand(plantId, 2026, WorkweekMode.FIVE_DAY));

    assertThat(result.year()).isEqualTo(2026);
    assertThat(result.workweekMode()).isEqualTo(WorkweekMode.FIVE_DAY);
    verify(auditLog).record(any(), any());
  }

  @Test
  void createRejectsDuplicate() {
    var plantId = UUID.randomUUID();
    when(plants.existsById(plantId)).thenReturn(true);
    when(calendars.findByPlantIdAndYear(plantId, 2026)).thenReturn(Optional.of(
        new PlantWorkingCalendarEntity(UUID.randomUUID(), plantId, 2026, WorkweekMode.FIVE_DAY,
            Instant.now(clock), Instant.now(clock))));

    assertThatThrownBy(() -> service().create(manager(),
        new PlantWorkingCalendarService.CreateCalendarCommand(plantId, 2026, WorkweekMode.FIVE_DAY)))
        .isInstanceOf(DuplicateCalendarException.class);
  }

  @Test
  void createRejectsUnknownPlant() {
    var plantId = UUID.randomUUID();
    when(plants.existsById(plantId)).thenReturn(false);

    assertThatThrownBy(() -> service().create(manager(),
        new PlantWorkingCalendarService.CreateCalendarCommand(plantId, 2026, WorkweekMode.FIVE_DAY)))
        .isInstanceOf(PlantNotFoundForCalendarException.class);
  }

  private AuthenticatedUser manager() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "manager", ApplicationRole.MANAGER_MAINTENANCE);
  }
}