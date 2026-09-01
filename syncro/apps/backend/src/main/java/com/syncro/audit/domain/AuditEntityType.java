package com.syncro.audit.domain;

/**
 * Audit entity types. Mirrors the {@code ck_audit_log_entity_type} CHECK on
 * {@code audit_log} (V1__orm_foundation_schema.sql) — any new value must be added
 * to BOTH sides in the same change. Story 15-1: DEPARTMENT_MEMBER renamed to
 * DEPARTMENT_USER (department_users table), SPAREPART_STOCK kept for audit-label
 * compatibility while the storage table is inventory_stock_balances, and the
 * new-module values needed by the 16-x stories are pre-provisioned.
 */
public enum AuditEntityType {
  PLANT,
  MACHINE_GROUP,
  MACHINE,
  SPAREPART_TAXONOMY,
  SPAREPART,
  INSTALLATION,
  RESPONSIBILITY,
  ALERT,
  SPAREPART_PRICE_ENTRY,
  SECTION,
  TEAM,
  WORK_ORDER_CATEGORY,
  WORK_ORDER,
  REPAIR_SESSION,
  WORKORDER_ATTACHMENT,
  WORK_ORDER_TODO,
  WORKORDER_RATING,
  RATING_DIMENSION,
  PREVENTIVE_PROGRAM,
  PREVENTIVE_SCHEDULE,
  PREVENTIVE_CHECKLIST,
  PREVENTIVE_ATTACHMENT,
  SPAREPART_REQUEST,
  DEPARTMENT,
  DEPARTMENT_USER,
  USER,
  INVENTORY_LOCATION,
  INVENTORY_STOCK_BALANCE,
  INVENTORY_TRANSFER,
  INVENTORY_RESERVATION,
  MACHINE_AREA,
  JOB_TITLE,
  SYSTEM_ROLE,
  ROLE_PERMISSION_MAPPING,
  MENU_FEATURE,
  DOMAIN_CONTEXT,
  USER_JOB_BINDING,
  USER_ROLE_BINDING,
  PLANT_WORKING_CALENDAR,
  SIGNATURE_USE,
  SPAREPART_STOCK,
  SYNC_RUN,
  SYNC_QUARANTINE,
  WORKORDER_SIGNATURE,
  WORK_ASSIGNMENT,
  WORK_LOG,
  WORK_LOG_RATING,
  WORK_ORDER_QUALITY_RATING
}
