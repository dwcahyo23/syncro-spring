import type { AuthUserViewApplicationRole } from "@/lib/api/generated/model/authUserViewApplicationRole";

/** Department (org people unit) view — mirrors DepartmentService.DepartmentView. */
export interface DepartmentView {
  id: string;
  plantId: string;
  plantCode: string;
  plantName: string;
  name: string;
  spvId: string | null;
  mgId: string | null;
  active: boolean;
  memberCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateDepartmentRequest {
  plantId: string;
  name: string;
  spvId?: string | null;
  mgId?: string | null;
}

export interface UpdateDepartmentRequest {
  name: string;
  spvId?: string | null;
  mgId?: string | null;
  active: boolean;
}

export interface SetDepartmentMembersRequest {
  userIds: string[];
}

export interface DepartmentListResponse {
  items: DepartmentView[];
}

/** User master view — mirrors AuthUserView (extended with master fields). */
export interface UserMasterView {
  id: string;
  loginIdentifier: string;
  displayName: string | null;
  nik: string | null;
  phoneNumber: string | null;
  applicationRole: AuthUserViewApplicationRole;
  enabled: boolean;
  jobTitleId: string | null;
  departmentId: string | null;
}

export interface UpdateUserMasterRequest {
  displayName?: string | null;
  nik?: string | null;
  phoneNumber?: string | null;
  jobTitleId?: string | null;
  departmentId?: string | null;
}

/** Job title master view. */
export interface JobTitleView {
  id: string;
  code: string;
  name: string;
  description: string | null;
}

export interface JobTitleListResponse {
  items: JobTitleView[];
}

/** Section view (extended with leaderUserId). */
export interface SectionViewWithLeader {
  id: string;
  plantId: string;
  plantCode: string;
  plantName: string;
  code: string;
  name: string;
  active: boolean;
  leaderUserId: string | null;
  createdAt: string;
  updatedAt: string;
}

/** System role (read-only config view, story 16-2). */
export interface SystemRoleView {
  id: string;
  code: string;
  name: string;
  level: number;
  active: boolean;
  description: string | null;
}

export interface SystemRoleListResponse {
  items: SystemRoleView[];
}

/** User job/role bindings (story 16-3/16-5). */
export interface JobBindingView {
  id: string;
  jobTitleId: string;
}

export interface RoleBindingView {
  id: string;
  systemRoleId: string;
  override: boolean;
}

export interface UserBindingsView {
  job: JobBindingView | null;
  roles: RoleBindingView[];
}

export interface SetJobRequest {
  jobTitleId: string | null;
}

export interface AddRoleRequest {
  systemRoleId: string;
  isOverride: boolean;
}

/** Organization hierarchy read. */
export interface OrganizationHierarchyView {
  plants: Array<{
    id: string;
    code: string;
    name: string;
    departments: Array<{
      id: string;
      name: string;
      spvId: string | null;
      mgId: string | null;
      active: boolean;
      members: Array<{ userId: string }>;
    }>;
    sections: Array<{
      id: string;
      code: string;
      name: string;
      leaderUserId: string | null;
      machineGroupIds: string[];
    }>;
  }>;
}
