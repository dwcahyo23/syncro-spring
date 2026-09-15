package com.syncro.report.domain;

/**
 * The two global print-layout kinds (spec "Template Laporan Cetak"): one row per
 * type in {@code report_print_templates}. API contract values are the uppercase
 * enum names, persisted verbatim (project rule: enum strings are contracts).
 */
public enum ReportPrintTemplateType {
  WORK_ORDER,
  PREVENTIVE
}
