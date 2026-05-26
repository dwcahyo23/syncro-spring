export const AUTH_TOKEN_COOKIE = "syncro_auth_token";
export const AUTH_USER_COOKIE = "syncro_auth_user";

export type AuthUser = {
  id: string;
  loginIdentifier: string;
  applicationRole: "SUPER_ADMIN" | "MANAGE" | "VIEWER";
};

export type LoginResult = {
  tokenType: "Bearer";
  accessToken: string;
  expiresInSeconds: number;
  user: AuthUser;
};
