package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.infrastructure.TeamEntity;
import com.syncro.org.infrastructure.TeamMachineEntity;
import com.syncro.org.infrastructure.TeamMachineRepository;
import com.syncro.org.infrastructure.TeamMemberEntity;
import com.syncro.org.infrastructure.TeamMemberRepository;
import com.syncro.org.infrastructure.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-plant teams (AD-13). Phase 1 role gate is {@code SUPER_ADMIN|MANAGE} for
 * ALL endpoints — mutations AND reads alike (review decision 2026-08-25: un-gated
 * reads leaked cross-plant machine metadata and member identifiers past the plant
 * gate; 9.4 maps MANAGE to MANAGER_MAINTENANCE). Expiry is validated at mutation
 * time against the injected {@link Clock} and evaluated lazily at scope-derive
 * time — no scheduled job. Member/machine link changes are audit-logged as TEAM
 * UPDATE with action-hint maps only when a change actually occurred; concurrent
 * duplicate links are absorbed idempotently. Audit rows carry {@code plantId=null}:
 * a cross-plant team by definition spans plants.
 */
@Service
public class TeamService {

  private static final String TEAM_NAME_UNIQUE_INDEX = "uq_teams_lower_name";

  private final TeamRepository teams;
  private final TeamMemberRepository members;
  private final TeamMachineRepository machines;
  private final AuthUserRepository users;
  private final MachineRepository machineRepository;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public TeamService(
      TeamRepository teams,
      TeamMemberRepository members,
      TeamMachineRepository machines,
      AuthUserRepository users,
      MachineRepository machineRepository,
      AuditLogWriter auditLog,
      Clock clock) {
    this.teams = teams;
    this.members = members;
    this.machines = machines;
    this.users = users;
    this.machineRepository = machineRepository;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public TeamListView list(AuthenticatedUser user) {
    requireManageRole(user);
    var now = Instant.now(clock);
    return new TeamListView(teams.findAllByOrderByNameAsc().stream()
        .map(team -> toView(team, members.countByIdTeamId(team.getId()), machines.countByIdTeamId(team.getId()), now))
        .toList());
  }

  @Transactional(readOnly = true)
  public TeamDetailView get(AuthenticatedUser user, UUID teamId) {
    requireManageRole(user);
    var team = teams.findById(teamId).orElseThrow(TeamNotFoundException::new);
    var now = Instant.now(clock);
    var memberViews = members.findAllByTeamId(teamId).stream()
        .map(member -> new TeamMemberView(member.getUser().getId(), member.getUser().getLoginIdentifier()))
        .toList();
    var machineViews = machines.findAllByTeamId(teamId).stream()
        .map(this::toMachineView)
        .toList();
    return new TeamDetailView(
        team.getId(), team.getName(), team.getExpiresAt(), isActive(team, now), memberViews, machineViews,
        team.getCreatedAt(), team.getUpdatedAt());
  }

  @Transactional
  public TeamView create(AuthenticatedUser user, CreateTeamCommand command) {
    requireManageRole(user);
    var name = normalizeName(command.name());
    if (teams.existsByNameIgnoreCase(name)) {
      throw new DuplicateTeamNameException();
    }
    var expiresAt = requireFutureExpiry(command.expiresAt());
    var now = Instant.now(clock);
    var team = saveTeam(new TeamEntity(UUID.randomUUID(), name, expiresAt, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.TEAM, team.getId(), team.getName(),
        null, null, TeamAuditValues.of(team, 0, 0), null));
    return toView(team, 0, 0, now);
  }

  @Transactional
  public TeamView update(AuthenticatedUser user, UUID teamId, UpdateTeamCommand command) {
    requireManageRole(user);
    var team = teams.findById(teamId).orElseThrow(TeamNotFoundException::new);
    var name = normalizeName(command.name());
    if (teams.findByNameIgnoreCase(name).filter(existing -> !existing.getId().equals(teamId)).isPresent()) {
      throw new DuplicateTeamNameException();
    }
    var expiresAt = requireFutureExpiry(command.expiresAt());
    var memberCount = members.countByIdTeamId(teamId);
    var machineCount = machines.countByIdTeamId(teamId);
    var previous = TeamAuditValues.of(team, memberCount, machineCount);
    var now = Instant.now(clock);
    team.update(name, expiresAt, now);
    var saved = saveTeam(team);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.TEAM, teamId, team.getName(), null,
        previous, TeamAuditValues.of(saved, memberCount, machineCount), null));
    return toView(saved, memberCount, machineCount, now);
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID teamId) {
    requireManageRole(user);
    var team = teams.findById(teamId).orElseThrow(TeamNotFoundException::new);
    var previous = TeamAuditValues.of(team, members.countByIdTeamId(teamId), machines.countByIdTeamId(teamId));
    // The DB FKs cascade, but JPA must not flush the team delete while link rows still
    // reference it — remove the links first within the same transaction.
    members.deleteByIdTeamId(teamId);
    machines.deleteByIdTeamId(teamId);
    members.flush();
    machines.flush();
    teams.delete(team);
    teams.flush();
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.TEAM, teamId, team.getName(), null,
        previous, null, null));
  }

  @Transactional
  public void addMember(AuthenticatedUser user, UUID teamId, UUID userId) {
    requireManageRole(user);
    var team = teams.findById(teamId).orElseThrow(TeamNotFoundException::new);
    if (!users.existsById(userId)) {
      throw new UserNotFoundException();
    }
    var memberCountBefore = members.countByIdTeamId(teamId);
    if (!members.existsByIdTeamIdAndIdUserId(teamId, userId)) {
      saveLinkIgnoringDuplicate(() -> members.saveAndFlush(new TeamMemberEntity(team, users.getReferenceById(userId))));
    }
    var memberCountAfter = members.countByIdTeamId(teamId);
    auditLinkIfChanged(user, team, memberCountBefore, memberCountAfter,
        machines.countByIdTeamId(teamId), machines.countByIdTeamId(teamId), "memberAdded");
  }

  @Transactional
  public void removeMember(AuthenticatedUser user, UUID teamId, UUID userId) {
    requireManageRole(user);
    var team = teams.findById(teamId).orElseThrow(TeamNotFoundException::new);
    if (!users.existsById(userId)) {
      throw new UserNotFoundException();
    }
    var memberCountBefore = members.countByIdTeamId(teamId);
    members.deleteByIdTeamIdAndIdUserId(teamId, userId);
    members.flush();
    var memberCountAfter = members.countByIdTeamId(teamId);
    auditLinkIfChanged(user, team, memberCountBefore, memberCountAfter,
        machines.countByIdTeamId(teamId), machines.countByIdTeamId(teamId), "memberRemoved");
  }

  @Transactional
  public void linkMachine(AuthenticatedUser user, UUID teamId, UUID machineId) {
    requireManageRole(user);
    var team = teams.findById(teamId).orElseThrow(TeamNotFoundException::new);
    if (!machineRepository.existsById(machineId)) {
      throw new MachineNotFoundException();
    }
    var machineCountBefore = machines.countByIdTeamId(teamId);
    if (!machines.existsByIdTeamIdAndIdMachineId(teamId, machineId)) {
      saveLinkIgnoringDuplicate(() -> machines.saveAndFlush(new TeamMachineEntity(team,
          machineRepository.getReferenceById(machineId))));
    }
    var machineCountAfter = machines.countByIdTeamId(teamId);
    auditLinkIfChanged(user, team, members.countByIdTeamId(teamId), members.countByIdTeamId(teamId),
        machineCountBefore, machineCountAfter, "machineLinked");
  }

  @Transactional
  public void unlinkMachine(AuthenticatedUser user, UUID teamId, UUID machineId) {
    requireManageRole(user);
    var team = teams.findById(teamId).orElseThrow(TeamNotFoundException::new);
    if (!machineRepository.existsById(machineId)) {
      throw new MachineNotFoundException();
    }
    var machineCountBefore = machines.countByIdTeamId(teamId);
    machines.deleteByIdTeamIdAndIdMachineId(teamId, machineId);
    machines.flush();
    var machineCountAfter = machines.countByIdTeamId(teamId);
    auditLinkIfChanged(user, team, members.countByIdTeamId(teamId), members.countByIdTeamId(teamId),
        machineCountBefore, machineCountAfter, "machineUnlinked");
  }

  /**
   * Concurrent duplicate link (two racing POSTs passing the exists-check) hits the
   * composite-PK constraint at flush; that specific violation IS the idempotent
   * success path. Any other integrity failure (e.g. the referenced row vanished)
   * still surfaces as {@link TeamDataIntegrityException}.
   */
  private void saveLinkIgnoringDuplicate(Runnable save) {
    try {
      save.run();
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (!message.contains("team_members_pkey") && !message.contains("team_machines_pkey")) {
        throw new TeamDataIntegrityException();
      }
    }
  }

  /** No-op link calls must not pollute the immutable audit trail with unchanged snapshots. */
  private void auditLinkIfChanged(AuthenticatedUser user, TeamEntity team, long memberCountBefore,
      long memberCountAfter, long machineCountBefore, long machineCountAfter, String action) {
    if (memberCountBefore == memberCountAfter && machineCountBefore == machineCountAfter) {
      return;
    }
    auditLink(user, team, memberCountBefore, memberCountAfter, machineCountBefore, machineCountAfter, action);
  }

  private void auditLink(AuthenticatedUser user, TeamEntity team, long memberCountBefore, long memberCountAfter,
      long machineCountBefore, long machineCountAfter, String action) {
    var previous = new LinkedHashMap<String, Object>();
    previous.put("action", action);
    previous.put("memberCount", memberCountBefore);
    previous.put("machineCount", machineCountBefore);
    var current = new LinkedHashMap<String, Object>();
    current.put("action", action);
    current.put("memberCount", memberCountAfter);
    current.put("machineCount", machineCountAfter);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.TEAM, team.getId(), team.getName(),
        null, previous, current, null));
  }

  /** Phase 1 gate: every team endpoint — read or write — requires SUPER_ADMIN|MANAGE. */
  private void requireManageRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new TeamMutationForbiddenException();
    }
  }

  private TeamEntity saveTeam(TeamEntity team) {
    try {
      return teams.saveAndFlush(team);
    } catch (DataIntegrityViolationException exception) {
      if (isUniqueNameViolation(exception)) {
        throw new DuplicateTeamNameException();
      }
      throw new TeamDataIntegrityException();
    }
  }

  private boolean isUniqueNameViolation(DataIntegrityViolationException exception) {
    var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
    return message.contains(TEAM_NAME_UNIQUE_INDEX);
  }

  private String normalizeName(String name) {
    return name == null ? null : name.trim();
  }

  private Instant requireFutureExpiry(Instant expiresAt) {
    if (expiresAt == null || !expiresAt.isAfter(Instant.now(clock))) {
      throw new TeamExpiryInPastException();
    }
    return expiresAt;
  }

  private boolean isActive(TeamEntity team, Instant now) {
    return team.getExpiresAt().isAfter(now);
  }

  private TeamView toView(TeamEntity team, long memberCount, long machineCount, Instant now) {
    return new TeamView(
        team.getId(),
        team.getName(),
        team.getExpiresAt(),
        isActive(team, now),
        memberCount,
        machineCount,
        team.getCreatedAt(),
        team.getUpdatedAt());
  }

  private TeamMachineView toMachineView(TeamMachineEntity link) {
    var machine = link.getMachine();
    var plant = machine.getPlant();
    var group = machine.getMachineGroup();
    return new TeamMachineView(
        machine.getId(),
        machine.getCode(),
        machine.getName(),
        plant.getId(),
        plant.getCode(),
        group.getId(),
        group.getName());
  }

  public record CreateTeamCommand(String name, Instant expiresAt) {
  }

  public record UpdateTeamCommand(String name, Instant expiresAt) {
  }

  public record TeamView(
      UUID id,
      String name,
      Instant expiresAt,
      boolean active,
      long memberCount,
      long machineCount,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record TeamListView(List<TeamView> items) {
  }

  public record TeamMemberView(UUID userId, String loginIdentifier) {
  }

  public record TeamMachineView(
      UUID machineId,
      String code,
      String name,
      UUID plantId,
      String plantCode,
      UUID machineGroupId,
      String machineGroupName) {
  }

  public record TeamDetailView(
      UUID id,
      String name,
      Instant expiresAt,
      boolean active,
      List<TeamMemberView> members,
      List<TeamMachineView> machines,
      Instant createdAt,
      Instant updatedAt) {
  }

  public static class DuplicateTeamNameException extends RuntimeException {
  }

  public static class TeamExpiryInPastException extends RuntimeException {
  }

  public static class TeamDataIntegrityException extends RuntimeException {
  }

  public static class TeamMutationForbiddenException extends RuntimeException {
  }

  public static class TeamNotFoundException extends RuntimeException {
  }

  public static class UserNotFoundException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }
}
