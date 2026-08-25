package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.application.TeamService.CreateTeamCommand;
import com.syncro.org.application.TeamService.DuplicateTeamNameException;
import com.syncro.org.application.TeamService.MachineNotFoundException;
import com.syncro.org.application.TeamService.TeamExpiryInPastException;
import com.syncro.org.application.TeamService.TeamMutationForbiddenException;
import com.syncro.org.application.TeamService.TeamNotFoundException;
import com.syncro.org.application.TeamService.UpdateTeamCommand;
import com.syncro.org.application.TeamService.UserNotFoundException;
import com.syncro.org.infrastructure.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class TeamServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private TeamService teamService;

  @Autowired
  private TeamRepository teams;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbc;

  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("9.2-SVC-001 P0 VIEWER cannot call any team endpoint (mutation or read)")
  void viewerCannotCallAnyTeamEndpoint() {
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-team@syncro.dev");
    var teamId = UUID.randomUUID();
    var command = new CreateTeamCommand("Cross Repair", future(30));

    assertThatThrownBy(() -> teamService.create(viewer, command))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.update(viewer, teamId, new UpdateTeamCommand("X", future(30))))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.delete(viewer, teamId))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.addMember(viewer, teamId, UUID.randomUUID()))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.removeMember(viewer, teamId, UUID.randomUUID()))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.linkMachine(viewer, teamId, UUID.randomUUID()))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.unlinkMachine(viewer, teamId, UUID.randomUUID()))
        .isInstanceOf(TeamMutationForbiddenException.class);
    // Review decision 2026-08-25: reads are gated identically to mutations.
    assertThatThrownBy(() -> teamService.list(viewer))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.get(viewer, teamId))
        .isInstanceOf(TeamMutationForbiddenException.class);
  }

  @Test
  @DisplayName("9.2-SVC-002 P1 MANAGE creates a team and audits TEAM CREATE with null plant")
  void manageCreatesTeamAndAudits() {
    var manager = persistedUser(ApplicationRole.MANAGE, "manage-team@syncro.dev");

    var created = teamService.create(manager, new CreateTeamCommand(" Cross Repair ", future(30)));

    assertThat(created.name()).isEqualTo("Cross Repair");
    assertThat(created.expiresAt()).isAfter(Instant.now());
    assertThat(created.active()).isTrue();
    assertThat(created.memberCount()).isZero();
    assertThat(created.machineCount()).isZero();
    assertThat(teams.findById(created.id())).isPresent();
    entityManager.flush();
    assertThat(auditCount("TEAM", "CREATE", manager.id())).isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'TEAM' AND action = 'CREATE' AND plant_id IS NULL",
        Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-003 P0 duplicate team name is rejected case-insensitively")
  void duplicateTeamNameRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    teamService.create(admin, new CreateTeamCommand("Cross Repair", future(30)));

    assertThatThrownBy(() -> teamService.create(admin,
        new CreateTeamCommand("cross repair", future(30))))
        .isInstanceOf(DuplicateTeamNameException.class);
  }

  @Test
  @DisplayName("9.2-SVC-004 P0 expiry in the past is rejected")
  void expiryInPastRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> teamService.create(admin,
        new CreateTeamCommand("Expired Team", Instant.now().minusSeconds(60))))
        .isInstanceOf(TeamExpiryInPastException.class);
  }

  @Test
  @DisplayName("9.2-SVC-005 P1 update renames and extends expiry and audits TEAM UPDATE")
  void updateRenamesAndExtendsExpiry() {
    var manager = persistedUser(ApplicationRole.MANAGE, "manage-team-update@syncro.dev");
    var created = teamService.create(manager, new CreateTeamCommand("Cross Repair", future(30)));

    var updated = teamService.update(manager, created.id(),
        new UpdateTeamCommand("Extended Repair", future(60)));

    assertThat(updated.name()).isEqualTo("Extended Repair");
    assertThat(updated.expiresAt()).isAfter(created.expiresAt());
    entityManager.flush();
    assertThat(auditCount("TEAM", "UPDATE", manager.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-006 P1 expiry can be shortened while still in the future")
  void shortenExpiryAllowed() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Shortenable", future(60)));
    var shorterExpiry = Instant.now().plusSeconds(3600);

    var updated = teamService.update(admin, created.id(), new UpdateTeamCommand("Shortenable", shorterExpiry));

    assertThat(updated.expiresAt()).isBefore(created.expiresAt());
    assertThat(updated.active()).isTrue();
  }

  @Test
  @DisplayName("9.2-SVC-007 P1 update to another team's name is rejected")
  void updateDuplicateNameRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    teamService.create(admin, new CreateTeamCommand("First", future(30)));
    var second = teamService.create(admin, new CreateTeamCommand("Second", future(30)));

    assertThatThrownBy(() -> teamService.update(admin, second.id(),
        new UpdateTeamCommand("first", future(30))))
        .isInstanceOf(DuplicateTeamNameException.class);
  }

  @Test
  @DisplayName("9.2-SVC-008 P1 update unknown team throws TeamNotFoundException")
  void updateUnknownTeamThrows() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> teamService.update(admin, UUID.randomUUID(),
        new UpdateTeamCommand("Whatever", future(30))))
        .isInstanceOf(TeamNotFoundException.class);
  }

  @Test
  @DisplayName("9.2-SVC-009 P0 deleting a team cascades members and machines and audits TEAM DELETE")
  void deleteCascadesAndAudits() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Cascade Team", future(30)));
    var member = persistedUser(ApplicationRole.VIEWER, "cascade-member@syncro.dev");
    teamService.addMember(admin, created.id(), UUID.fromString(member.id()));
    var machine = seedMachine("M-" + seedSeq.incrementAndGet());
    teamService.linkMachine(admin, created.id(), machine.getId());

    teamService.delete(admin, created.id());

    assertThat(teams.findById(created.id())).isEmpty();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_members WHERE team_id = ?", Long.class, created.id())).isZero();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_machines WHERE team_id = ?", Long.class, created.id())).isZero();
    entityManager.flush();
    assertThat(auditCount("TEAM", "DELETE", admin.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-010 P0 adding a member persists it and audits TEAM UPDATE with memberAdded")
  void addMemberPersistsAndAudits() {
    var manager = persistedUser(ApplicationRole.MANAGE, "manage-team-member@syncro.dev");
    var created = teamService.create(manager, new CreateTeamCommand("Member Team", future(30)));
    var member = persistedUser(ApplicationRole.VIEWER, "member@syncro.dev");

    teamService.addMember(manager, created.id(), UUID.fromString(member.id()));

    assertThat(teams.findById(created.id())).isPresent();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_members WHERE team_id = ?", Long.class, created.id())).isEqualTo(1L);
    entityManager.flush();
    assertThat(auditActionHint("memberAdded", created.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-011 P0 adding the same member again is idempotent")
  void addMemberDuplicateIdempotent() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Dup Member Team", future(30)));
    var member = persistedUser(ApplicationRole.VIEWER, "dup-member@syncro.dev");

    teamService.addMember(admin, created.id(), UUID.fromString(member.id()));
    teamService.addMember(admin, created.id(), UUID.fromString(member.id()));

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_members WHERE team_id = ?", Long.class, created.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-012 P0 removing a member deletes the link")
  void removeMemberDeletes() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Remove Member Team", future(30)));
    var member = persistedUser(ApplicationRole.VIEWER, "remove-member@syncro.dev");
    teamService.addMember(admin, created.id(), UUID.fromString(member.id()));

    teamService.removeMember(admin, created.id(), UUID.fromString(member.id()));

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_members WHERE team_id = ?", Long.class, created.id())).isZero();
    entityManager.flush();
    assertThat(auditActionHint("memberRemoved", created.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-013 P0 removing a non-member (existing user) is idempotent and writes no audit")
  void removeNonMemberIdempotent() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Remove Missing Team", future(30)));
    var member = persistedUser(ApplicationRole.VIEWER, "remove-missing@syncro.dev");

    teamService.removeMember(admin, created.id(), UUID.fromString(member.id()));

    entityManager.flush();
    assertThat(auditActionHint("memberRemoved", created.id())).isZero();
  }

  @Test
  @DisplayName("9.2-SVC-014 P0 unknown user on member add/remove throws UserNotFoundException")
  void unknownUserThrows() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Unknown User Team", future(30)));

    assertThatThrownBy(() -> teamService.addMember(admin, created.id(), UUID.randomUUID()))
        .isInstanceOf(UserNotFoundException.class);
    assertThatThrownBy(() -> teamService.removeMember(admin, created.id(), UUID.randomUUID()))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  @DisplayName("9.2-SVC-015 P0 linking a machine persists it and audits machineLinked")
  void linkMachinePersistsAndAudits() {
    var manager = persistedUser(ApplicationRole.MANAGE, "manage-team-machine@syncro.dev");
    var created = teamService.create(manager, new CreateTeamCommand("Machine Team", future(30)));
    var machine = seedMachine("M-" + seedSeq.incrementAndGet());

    teamService.linkMachine(manager, created.id(), machine.getId());

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_machines WHERE team_id = ?", Long.class, created.id())).isEqualTo(1L);
    entityManager.flush();
    assertThat(auditActionHint("machineLinked", created.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-016 P0 linking the same machine again is idempotent")
  void linkMachineDuplicateIdempotent() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Dup Machine Team", future(30)));
    var machine = seedMachine("M-" + seedSeq.incrementAndGet());

    teamService.linkMachine(admin, created.id(), machine.getId());
    teamService.linkMachine(admin, created.id(), machine.getId());

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_machines WHERE team_id = ?", Long.class, created.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-017 P0 unlinking a machine removes the link")
  void unlinkMachineRemoves() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Unlink Machine Team", future(30)));
    var machine = seedMachine("M-" + seedSeq.incrementAndGet());
    teamService.linkMachine(admin, created.id(), machine.getId());

    teamService.unlinkMachine(admin, created.id(), machine.getId());

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM team_machines WHERE team_id = ?", Long.class, created.id())).isZero();
    entityManager.flush();
    assertThat(auditActionHint("machineUnlinked", created.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.2-SVC-018 P0 unknown machine on link/unlink throws MachineNotFoundException")
  void unknownMachineThrows() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Unknown Machine Team", future(30)));

    assertThatThrownBy(() -> teamService.linkMachine(admin, created.id(), UUID.randomUUID()))
        .isInstanceOf(MachineNotFoundException.class);
    assertThatThrownBy(() -> teamService.unlinkMachine(admin, created.id(), UUID.randomUUID()))
        .isInstanceOf(MachineNotFoundException.class);
  }

  @Test
  @DisplayName("9.2-SVC-019 P1 list returns all teams sorted by name with computed active flag")
  void listReturnsTeamsWithActiveFlag() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    teamService.create(admin, new CreateTeamCommand("Zulu Team", future(30)));
    teamService.create(admin, new CreateTeamCommand("Alpha Team", future(30)));
    // A past-expiry team (seeded directly — creation rejects past expiries) proves the
    // computed active flag reflects lazy expiry evaluation.
    var expiredId = UUID.randomUUID();
    var now = Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO teams (id, name, expires_at, version, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        expiredId, "Expired Direct", Timestamp.from(Instant.now().minusSeconds(60)), 0, now, now);

    var result = teamService.list(admin);

    assertThat(result.items()).extracting(t -> t.name())
        .containsExactly("Alpha Team", "Expired Direct", "Zulu Team");
    assertThat(result.items().stream().filter(t -> t.id().equals(expiredId)).findFirst().orElseThrow().active())
        .isFalse();
  }

  @Test
  @DisplayName("9.2-SVC-020 P1 get returns detail with members and machines")
  void getReturnsDetailWithMembersAndMachines() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = teamService.create(admin, new CreateTeamCommand("Detail Team", future(30)));
    var member = persistedUser(ApplicationRole.VIEWER, "detail-member@syncro.dev");
    teamService.addMember(admin, created.id(), UUID.fromString(member.id()));
    var machine = seedMachine("M-" + seedSeq.incrementAndGet());
    teamService.linkMachine(admin, created.id(), machine.getId());

    var detail = teamService.get(admin, created.id());

    assertThat(detail.name()).isEqualTo("Detail Team");
    assertThat(detail.members()).hasSize(1);
    assertThat(detail.members().get(0).userId()).isEqualTo(UUID.fromString(member.id()));
    assertThat(detail.members().get(0).loginIdentifier()).isEqualTo("detail-member@syncro.dev");
    assertThat(detail.machines()).hasSize(1);
    assertThat(detail.machines().get(0).machineId()).isEqualTo(machine.getId());
    assertThat(detail.machines().get(0).code()).isEqualTo(machine.getCode());
  }

  @Test
  @DisplayName("9.2-SVC-021 P1 get unknown team throws TeamNotFoundException")
  void getUnknownTeamThrows() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    assertThatThrownBy(() -> teamService.get(admin, UUID.randomUUID()))
        .isInstanceOf(TeamNotFoundException.class);
  }

  @Test
  @DisplayName("9.2-SVC-022 P0 list and get are forbidden for VIEWER (reads gated, review decision)")
  void readsForbiddenToViewer() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    teamService.create(admin, new CreateTeamCommand("Gated Team", future(30)));
    var viewer = authenticatedUser(ApplicationRole.VIEWER);

    assertThatThrownBy(() -> teamService.list(viewer))
        .isInstanceOf(TeamMutationForbiddenException.class);
    assertThatThrownBy(() -> teamService.get(viewer, UUID.randomUUID()))
        .isInstanceOf(TeamMutationForbiddenException.class);
  }

  private Long auditCount(String entityType, String action, String actorId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = ? AND action = ? AND actor_id = ?::uuid",
        Long.class, entityType, action, actorId);
  }

  private Long auditActionHint(String actionHint, UUID teamId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'TEAM' AND action = 'UPDATE' "
            + "AND entity_id = ?::uuid AND new_value LIKE ?",
        Long.class, teamId, "%\"" + actionHint + "\"%");
  }

  private MachineEntity seedMachine(String code) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "P-" + seedSeq.incrementAndGet(),
        "Plant " + seedSeq.incrementAndGet(), now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant,
        "Group " + seedSeq.incrementAndGet(), now, now));
    return machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, code, "Machine " + code,
        MachineStatus.ACTIVE, "Juki", null, null, java.util.List.of(), now, now));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        role,
        true,
        now,
        now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static Instant future(long days) {
    return Instant.now().plusSeconds(days * 24 * 3600);
  }
}
