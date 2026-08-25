export const AUTH_TOKEN_COOKIE = "syncro_auth_token";
export const AUTH_USER_COOKIE = "syncro_auth_user";

export type AuthUser = {
  id: string;
  loginIdentifier: string;
  applicationRole:
    | "SUPER_ADMIN"
    | "MANAGER_MAINTENANCE"
    | "MAINTENANCE_LEADER"
    | "SECTION_LEADER"
    | "STAFF_MAINTENANCE"
    | "TECHNICIAN"
    | "INVENTORY_MAINTENANCE"
    | "STOREKEEPER"
    | "PRODUCTION_LEADER"
    | "AUDITOR";
};

export type LoginResult = {
  tokenType: "Bearer";
  accessToken: string;
  expiresInSeconds: number;
  user: AuthUser;
};
