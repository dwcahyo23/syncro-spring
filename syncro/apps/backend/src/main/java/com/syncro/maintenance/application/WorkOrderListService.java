package com.syncro.maintenance.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderListRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScopeService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder list read (workorder-table story): server-paginated, scope-filtered workorders
 * ordered by {@code createdAt desc}. Reuses the {@code findKanbanRows} scope join
 * (plant OR machine-group, SUPER_ADMIN unrestricted) WITHOUT todos — list rows carry no
 * todos so paginating hundreds of workorders never hits a cartesian blowup. Optional
 * filters: {@code from}/{@code to} ISO dates matched against {@code createdAt} (server
 * clock UTC — boundary = start-of-day/end-of-day), a single status, one machine, and a
 * case-insensitive search across the workorder id, machine code/name and category label.
 * Technician names are resolved in one {@code findAllById} call (≤ pageSize rows).
 *
 * <p>Read posture: any authenticated user may list (the workorder read posture), with
 * row-level scope filtering here; OPA enforces the read-allowed gate.
 */
@Service
public class WorkOrderListService {

  private final WorkOrderRepository workOrders;
  private final AuthUserRepository users;
  private final OperationalScopeService scopes;

  public WorkOrderListService(WorkOrderRepository workOrders, AuthUserRepository users,
      OperationalScopeService scopes) {
    this.workOrders = workOrders;
    this.users = users;
    this.scopes = scopes;
  }

  @Transactional(readOnly = true)
  public Page<WorkOrderListView> list(AuthenticatedUser user, String from, String to, WorkOrderStatus status,
      UUID machineId, String search, int page, int size) {
    validatePageAndSize(page, size);
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = new java.util.HashSet<UUID>();
    groupIds.addAll(scope.machineGroupIds());
    groupIds.addAll(scope.activeTeamIds());

    var fromInstant = parseDate(from, "from");
    var toInstant = parseDateExclusive(to, "to");
    var searchPattern = like(search);

    var rows = workOrders.findScopedPage(
        unrestricted,
        scope.plantIds() == null ? List.of() : scope.plantIds(),
        groupIds,
        fromInstant,
        toInstant,
        status,
        machineId,
        searchPattern,
        PageRequest.of(page, size));
    var total = workOrders.countScoped(
        unrestricted,
        scope.plantIds() == null ? List.of() : scope.plantIds(),
        groupIds,
        fromInstant,
        toInstant,
        status,
        machineId,
        searchPattern);

    var technicianNames = resolveTechnicianNames(rows);
    var items = rows.stream().map(row -> toView(row, technicianNames)).toList();
    return new Page<>(items, total, page, size);
  }

  /** One {@code findAllById} over the assigned technician ids on this page (no N+1). */
  private java.util.Map<UUID, String> resolveTechnicianNames(List<WorkOrderListRow> rows) {
    var technicianIds = new LinkedHashSet<UUID>();
    for (var row : rows) {
      var assignedId = row.workOrder().getAssignedTechnicianId();
      if (assignedId != null) {
        technicianIds.add(assignedId);
      }
    }
    if (technicianIds.isEmpty()) {
      return java.util.Map.of();
    }
    var nameById = new java.util.HashMap<UUID, String>();
    for (var user : users.findAllById(technicianIds)) {
      nameById.put(user.getId(), displayName(user));
    }
    return nameById;
  }

  private static String displayName(com.syncro.auth.infrastructure.AuthUserEntity user) {
    var displayName = user.getDisplayName();
    if (displayName != null && !displayName.isBlank()) {
      return displayName.trim();
    }
    return user.getLoginIdentifier();
  }

  private static WorkOrderListView toView(WorkOrderListRow row, java.util.Map<UUID, String> technicianNames) {
    var workOrder = row.workOrder();
    var assignedId = workOrder.getAssignedTechnicianId();
    return new WorkOrderListView(workOrder.getId(), workOrder.getStatus(),
        row.category() != null ? row.category().getCode() : null,
        row.category() != null ? row.category().getLabel() : null,
        row.machineCode(), row.machineName(), row.plantCode(), assignedId,
        assignedId != null ? technicianNames.get(assignedId) : null,
        trim(workOrder.getDescription()), workOrder.getCreatedAt(), workOrder.getUpdatedAt(),
        workOrder.getDoneReason());
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    var trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** {@code page} is 0-based, {@code size} is 1..200; anything else → 400 VALIDATION_ERROR. */
  private static void validatePageAndSize(int page, int size) {
    var fieldErrors = new java.util.LinkedHashMap<String, String>();
    if (page < 0) {
      fieldErrors.put("page", "Page must be 0 or greater.");
    }
    if (size < 1 || size > 200) {
      fieldErrors.put("size", "Size must be between 1 and 200.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new WorkOrderListValidationException(fieldErrors);
    }
  }

  /** ISO date ({@code yyyy-MM-dd}) → UTC start-of-day; invalid → 400 VALIDATION_ERROR on the field. */
  private static Instant parseDate(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException exception) {
      throw new WorkOrderListValidationException(Map.of(field, "Date must be in yyyy-MM-dd format."));
    }
  }

  /** {@code to} is inclusive → the query uses an exclusive upper bound of {@code to+1 day}. */
  private static Instant parseDateExclusive(String value, String field) {
    var instant = parseDate(value, field);
    return instant != null ? instant.plusSeconds(86_400) : null;
  }

  /** {@code %term%} with {@code _}, {@code %} and {@code \} escaped (same rule as machine search). */
  private static String like(String term) {
    if (term == null || term.isBlank()) {
      return null;
    }
    var escaped = term.trim()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_");
    return "%" + escaped.toLowerCase() + "%";
  }

  /**
   * Server-paginated result envelope. {@code items} is the current page, {@code total}
   * the matching count (the count query), {@code page} the 0-based page and {@code size}
   * the page size echoed back for the frontend contract.
   */
  public record Page<T>(List<T> items, long total, int page, int size) {
  }

  /** One list row view: workorder id/status/category/machine/plant/technician/timestamps. */
  public record WorkOrderListView(String id, WorkOrderStatus status, String categoryCode, String categoryLabel,
      String machineCode, String machineName, String plantCode, UUID assignedTechnicianId,
      String assignedTechnicianName, String description, Instant createdAt, Instant updatedAt, String doneReason) {
  }

  /** Invalid list query parameter (bad date format) → 400 VALIDATION_ERROR with fieldErrors. */
  public static class WorkOrderListValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public WorkOrderListValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new java.util.HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
