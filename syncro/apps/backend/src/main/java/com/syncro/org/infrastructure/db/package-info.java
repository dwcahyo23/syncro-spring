/**
 * JPA persistence for Modul A org tables (story 15-2): MachineArea,
 * PlantWorkingCalendar(+Date), SystemRole, RolePermissionMapping, MenuFeature,
 * DomainContext, UserJobBinding, UserRoleBinding. Departments/department_users/
 * job_titles remain in com.syncro.org.infrastructure (legacy story 6-x placement).
 * This package marker fixes the module boundary so cross-module imports stay
 * through public contracts (project context layering rules).
 */
package com.syncro.org.infrastructure.db;
