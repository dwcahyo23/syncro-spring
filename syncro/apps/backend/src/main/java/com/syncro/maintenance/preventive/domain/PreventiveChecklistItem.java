package com.syncro.maintenance.preventive.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One checklist item (FR-132, story 11-2): an assessment line with optional LSL/USL
 * bounds where applicable. The checklist is captured at completion — there is no
 * separate template-management story, so items are stored with their result.
 */
public record PreventiveChecklistItem(
    UUID id,
    UUID resultId,
    short position,
    String label,
    String value,
    BigDecimal lsl,
    BigDecimal usl,
    String note) {
}
