package com.syncro.org.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.org.domain.WorkweekMode;
import com.syncro.org.infrastructure.JobTitleEntity;
import com.syncro.org.infrastructure.JobTitleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 15-2 convention proof for {@code com.syncro.org.infrastructure.db}: boots the
 * full Spring context (so {@code ddl-auto=validate} gates every new entity against the
 * V1 schema) and round-trips each org aggregate with its enum/nullable fields. Unique
 * codes carry a random suffix so a crashed prior run (container reuse) cannot collide.
 */
class OrgEntityConventionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-08-31T08:00:00Z");
  private static final Instant T1 = Instant.parse("2026-08-31T09:00:00Z");

  @Autowired
  private PlantRepository plants;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private JobTitleRepository jobTitles;
  @Autowired
  private SystemRoleRepository systemRoles;
  @Autowired
  private MenuFeatureRepository menuFeatures;
  @Autowired
  private DomainContextRepository domainContexts;
  @Autowired
  private RolePermissionMappingRepository rolePermissionMappings;
  @Autowired
  private UserJobBindingRepository userJobBindings;
  @Autowired
  private UserRoleBindingRepository userRoleBindings;
  @Autowired
  private MachineAreaRepository machineAreas;
  @Autowired
  private PlantWorkingCalendarRepository calendars;
  @Autowired
  private PlantWorkingCalendarDateRepository calendarDates;

  private PlantEntity plant() {
    var code = "P" + UUID.randomUUID().toString().substring(0, 8);
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, "Plant " + code, T0, T0));
  }

  private AuthUserEntity user(String login) {
    return users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(), login, "hash", ApplicationRole.TECHNICIAN, true, T0, T0));
  }

  @Test
  void systemRoleMenuFeatureAndDomainContextRoundTrip() {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var role = systemRoles.saveAndFlush(new SystemRoleEntity(
        UUID.randomUUID(), "SR-" + suffix, "Role " + suffix, 10, true, "desc", T0, T0));
    var feature = menuFeatures.saveAndFlush(new MenuFeatureEntity(
        UUID.randomUUID(), "cmms:wo:read-" + suffix, "cmms", "Read workorders", true, T0, T0));
    var domain = domainContexts.saveAndFlush(new DomainContextEntity(
        UUID.randomUUID(), "maintenance-" + suffix, "Maintenance", T0, T0));

    var reloadedRole = systemRoles.findById(role.getId()).orElseThrow();
    assertThat(reloadedRole.getCode()).isEqualTo("SR-" + suffix);
    assertThat(reloadedRole.getLevel()).isEqualTo(10);
    assertThat(reloadedRole.isActive()).isTrue();
    assertThat(systemRoles.findByCode("SR-" + suffix)).contains(reloadedRole);

    assertThat(menuFeatures.findByCode("cmms:wo:read-" + suffix)).contains(feature);
    assertThat(domainContexts.findByCode("maintenance-" + suffix)).contains(domain);
  }

  @Test
  void rolePermissionMappingPreservesNullDomainAndGrantsFlag() {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var role = systemRoles.saveAndFlush(new SystemRoleEntity(
        UUID.randomUUID(), "SR-" + suffix, "Role " + suffix, 5, true, null, T0, T0));
    var feature = menuFeatures.saveAndFlush(new MenuFeatureEntity(
        UUID.randomUUID(), "cmms:wo:write-" + suffix, "cmms", "Write workorders", true, T0, T0));
    var domain = domainContexts.saveAndFlush(new DomainContextEntity(
        UUID.randomUUID(), "inventory-" + suffix, "Inventory", T0, T0));

    var allDomains = rolePermissionMappings.saveAndFlush(new RolePermissionMappingEntity(
        UUID.randomUUID(), role.getId(), feature.getId(), null, true, T0, T0));
    var scopedDeny = rolePermissionMappings.saveAndFlush(new RolePermissionMappingEntity(
        UUID.randomUUID(), role.getId(), feature.getId(), domain.getId(), false, T0, T1));

    var reloadedAll = rolePermissionMappings.findById(allDomains.getId()).orElseThrow();
    assertThat(reloadedAll.getDomainId()).isNull();
    assertThat(reloadedAll.isGranted()).isTrue();

    var reloadedScoped = rolePermissionMappings.findById(scopedDeny.getId()).orElseThrow();
    assertThat(reloadedScoped.getDomainId()).isEqualTo(domain.getId());
    assertThat(reloadedScoped.isGranted()).isFalse();
    assertThat(reloadedScoped.getUpdatedAt()).isEqualTo(T1);
  }

  @Test
  void userJobAndRoleBindingsRoundTrip() {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var role = systemRoles.saveAndFlush(new SystemRoleEntity(
        UUID.randomUUID(), "SR-" + suffix, "Role " + suffix, 1, true, null, T0, T0));
    var jobTitle = jobTitles.saveAndFlush(new JobTitleEntity(
        UUID.randomUUID(), "JT-" + suffix, "Technician", null, T0, T0));
    var boundUser = user("bind-" + suffix + "@syncro.test");

    var jobBinding = userJobBindings.saveAndFlush(new UserJobBindingEntity(
        UUID.randomUUID(), boundUser.getId(), jobTitle.getId(), boundUser.getId(), T0));
    var roleBinding = userRoleBindings.saveAndFlush(new UserRoleBindingEntity(
        UUID.randomUUID(), boundUser.getId(), role.getId(), true, boundUser.getId(), T0));

    assertThat(userJobBindings.findById(jobBinding.getId()).orElseThrow().getJobTitleId())
        .isEqualTo(jobTitle.getId());
    var reloadedRoleBinding = userRoleBindings.findById(roleBinding.getId()).orElseThrow();
    assertThat(reloadedRoleBinding.getSystemRoleId()).isEqualTo(role.getId());
    assertThat(reloadedRoleBinding.isOverride()).isTrue();
    assertThat(reloadedRoleBinding.getAssignedAt()).isEqualTo(T0);
  }

  @Test
  void machineAreaRoundTripPreservesNullableCode() {
    var plant = plant();
    var area = machineAreas.saveAndFlush(new MachineAreaEntity(
        UUID.randomUUID(), plant.getId(), null, "Line 1 Area", "East wing", true, T0, T0));

    var reloaded = machineAreas.findById(area.getId()).orElseThrow();
    assertThat(reloaded.getPlantId()).isEqualTo(plant.getId());
    assertThat(reloaded.getCode()).isNull();
    assertThat(reloaded.getName()).isEqualTo("Line 1 Area");
    assertThat(reloaded.isActive()).isTrue();
    assertThat(reloaded.getCreatedAt()).isEqualTo(T0);
  }

  @Test
  void plantWorkingCalendarWithDatesRoundTrip() {
    var plant = plant();
    var calendar = calendars.saveAndFlush(new PlantWorkingCalendarEntity(
        UUID.randomUUID(), plant.getId(), 2026, WorkweekMode.SIX_DAY, T0, T0));
    calendarDates.saveAndFlush(new PlantWorkingCalendarDateEntity(
        UUID.randomUUID(), calendar.getId(), LocalDate.of(2026, 12, 25), "Christmas", T0, T0));
    calendarDates.saveAndFlush(new PlantWorkingCalendarDateEntity(
        UUID.randomUUID(), calendar.getId(), LocalDate.of(2027, 1, 1), null, T0, T0));

    var reloaded = calendars.findById(calendar.getId()).orElseThrow();
    assertThat(reloaded.getYear()).isEqualTo(2026);
    assertThat(reloaded.getWorkweekMode()).isEqualTo(WorkweekMode.SIX_DAY);
    assertThat(calendars.findByPlantIdAndYear(plant.getId(), 2026)).contains(reloaded);

    var dates = calendarDates.findByWorkingCalendarIdOrderByDateAsc(calendar.getId());
    assertThat(dates).hasSize(2);
    assertThat(dates.get(0).getDate()).isEqualTo(LocalDate.of(2026, 12, 25));
    assertThat(dates.get(0).getReason()).isEqualTo("Christmas");
    assertThat(dates.get(1).getReason()).isNull();
  }
}
