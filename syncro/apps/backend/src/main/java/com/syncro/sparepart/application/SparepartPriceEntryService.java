package com.syncro.sparepart.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JobScopeService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartPriceEntryService {
  private static final String JOB_SCOPE_LEVEL = "LEADER";
  private static final String DEFAULT_CURRENCY = "IDR";

  private final SparepartRepository spareparts;
  private final SparepartPriceEntryRepository priceEntries;
  private final AuthUserRepository users;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final JobScopeService jobScopes;

  public SparepartPriceEntryService(SparepartRepository spareparts, SparepartPriceEntryRepository priceEntries,
      AuthUserRepository users, AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog,
      Clock clock, JobScopeService jobScopes) {
    this.spareparts = spareparts;
    this.priceEntries = priceEntries;
    this.users = users;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
    this.jobScopes = jobScopes;
  }

  @Transactional(readOnly = true)
  public List<SparepartPriceEntryView> list(AuthenticatedUser user, UUID sparepartId) {
    var sparepart = findScopedSparepart(user, sparepartId);
    return priceEntries.findAllBySparepartIdOrderByEnteredAtDesc(sparepart.getId()).stream()
        .map(this::toView)
        .toList();
  }

  /**
   * Appends one immutable price entry. Gate order mirrors patchProcurement (Story 8-2):
   * app-role first, then LEADER-or-above job scope (SUPER_ADMIN bypasses), then plant masking.
   * {@code idrAmount} is always backend-derived; the client never supplies it.
   */
  @Transactional
  public SparepartPriceEntryView create(AuthenticatedUser user, UUID sparepartId, PriceEntryCommand command) {
    requireMutationRole(user);
    jobScopes.requireLevelOrAbove(user, JOB_SCOPE_LEVEL);
    var sparepart = findScopedSparepart(user, sparepartId);
    var normalized = normalize(command);
    var idrAmount = normalized.amount().multiply(normalized.kursToIdr()).setScale(2, RoundingMode.HALF_UP);
    var actor = users.findById(UUID.fromString(user.id())).orElseThrow(NotFoundException::new);
    var entity = new SparepartPriceEntryEntity(
        UUID.randomUUID(),
        sparepart,
        normalized.amount(),
        normalized.currency(),
        normalized.kursToIdr(),
        idrAmount,
        actor,
        Instant.now(clock));
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(
        AuditAction.CREATE,
        AuditEntityType.SPAREPART_PRICE_ENTRY,
        saved.getId(),
        sparepart.getCode(),
        sparepart.getMachine().getPlant().getId(),
        null,
        newValuesOf(sparepart, saved)));
    return toView(saved);
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new MutationForbiddenException();
    }
  }

  private SparepartEntity findScopedSparepart(AuthenticatedUser user, UUID sparepartId) {
    var sparepart = spareparts.findById(sparepartId).orElseThrow(NotFoundException::new);
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return sparepart;
    }
    var assignedPlantIds = assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .map(assignment -> assignment.getPlantId())
        .toList();
    if (!assignedPlantIds.contains(sparepart.getMachine().getPlant().getId())) {
      throw new NotFoundException();
    }
    return sparepart;
  }

  private PriceEntryCommand normalize(PriceEntryCommand command) {
    if (command.amount() == null || command.amount().signum() <= 0) {
      throw new ValidationException(Map.of("amount", "Amount must be greater than zero."));
    }
    var currency = command.currency() == null || command.currency().isBlank()
        ? DEFAULT_CURRENCY
        : command.currency().trim();
    if (!currency.matches("[A-Z]{3}")) {
      throw new ValidationException(Map.of("currency", "Currency must be an uppercase ISO-4217 code."));
    }
    BigDecimal kursToIdr;
    if (currency.equals(DEFAULT_CURRENCY)) {
      // Single source of truth for IDR math: submitted kurs is ignored and stored as exactly 1.
      kursToIdr = BigDecimal.ONE;
    } else if (command.kursToIdr() == null || command.kursToIdr().signum() <= 0) {
      throw new ValidationException(
          Map.of("kursToIdr", "Exchange rate to IDR is required for non-IDR currencies and must be positive."));
    } else {
      kursToIdr = command.kursToIdr();
    }
    return new PriceEntryCommand(command.amount(), currency, kursToIdr);
  }

  private Map<String, Object> newValuesOf(SparepartEntity sparepart, SparepartPriceEntryEntity saved) {
    var values = new LinkedHashMap<String, Object>();
    values.put("sparepartCode", sparepart.getCode());
    values.put("amount", saved.getAmount());
    values.put("currency", saved.getCurrency());
    values.put("kursToIdr", saved.getKursToIdr());
    values.put("idrAmount", saved.getIdrAmount());
    values.put("enteredAt", saved.getEnteredAt().toString());
    return values;
  }

  private SparepartPriceEntryEntity save(SparepartPriceEntryEntity entry) {
    try {
      return priceEntries.saveAndFlush(entry);
    } catch (DataIntegrityViolationException exception) {
      throw new DataIntegrityException();
    }
  }

  private SparepartPriceEntryView toView(SparepartPriceEntryEntity entry) {
    return new SparepartPriceEntryView(
        entry.getId(),
        entry.getSparepart().getId(),
        entry.getAmount(),
        entry.getCurrency(),
        entry.getKursToIdr(),
        entry.getIdrAmount(),
        entry.getEnteredBy().getId(),
        entry.getEnteredBy().getLoginIdentifier(),
        entry.getEnteredAt());
  }

  public record PriceEntryCommand(BigDecimal amount, String currency, BigDecimal kursToIdr) {
  }

  public record SparepartPriceEntryView(
      UUID id,
      UUID sparepartId,
      BigDecimal amount,
      String currency,
      BigDecimal kursToIdr,
      BigDecimal idrAmount,
      UUID enteredBy,
      String enteredByName,
      Instant enteredAt) {
  }

  public static class ValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class MutationForbiddenException extends RuntimeException {
  }

  /** Same 404 used for unknown ids and out-of-plant masking. */
  public static class NotFoundException extends RuntimeException {
  }

  public static class DataIntegrityException extends RuntimeException {
  }
}
