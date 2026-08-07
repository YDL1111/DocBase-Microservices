/**
 * Token / 当前用户存取层。
 *
 * 与旧前端的关键差异：
 *  - 旧：Token 存于 Cookie（authorized-token）+ sessionStorage，密码 RSA 加密后传；
 *  - 新：accessToken / refreshToken 均存于 sessionStorage，密码明文经 HTTPS 发送
 *        （后端使用 BCrypt 比较，不再需要前端 RSA 加密）。
 *
 * 安全约束：
 *  - refreshToken 绝不出现在 URL 中；
 *  - 日志与错误提示中不输出 Token 原文；
 *  - 客户端不信任、不伪造 X-User-* 身份头（由 Gateway 统一注入）。
 */
import {
  storage,
  TOKEN_KEY,
  REFRESH_TOKEN_KEY,
  USER_INFO_KEY
} from "./storage";
import type { UserInfo } from "@/api/types";

/** 获取 access token（请求头使用） */
export function getAccessToken(): string | null {
  return storage.getString(TOKEN_KEY);
}

/** 获取 refresh token（仅用于刷新接口请求体） */
export function getRefreshToken(): string | null {
  return storage.getString(REFRESH_TOKEN_KEY);
}

/** 当前登录用户信息（非敏感展示字段） */
export function getUserInfo(): UserInfo | null {
  return storage.get<UserInfo>(USER_INFO_KEY);
}

/** 一次性写入登录结果 */
export function setLoginResult(payload: {
  accessToken: string;
  refreshToken: string;
  userInfo?: UserInfo | null;
}): void {
  storage.set(TOKEN_KEY, payload.accessToken);
  storage.set(REFRESH_TOKEN_KEY, payload.refreshToken);
  if (payload.userInfo) {
    storage.set(USER_INFO_KEY, payload.userInfo);
  }
}

/** 仅更新 token（刷新后） */
export function setTokens(accessToken: string, refreshToken: string): void {
  storage.set(TOKEN_KEY, accessToken);
  storage.set(REFRESH_TOKEN_KEY, refreshToken);
}

/** 更新用户信息 */
export function setUserInfo(userInfo: UserInfo): void {
  storage.set(USER_INFO_KEY, userInfo);
}

/** 清除认证态（登出 / 被踢） */
export function removeToken(): void {
  storage.clearAuth();
}

/** 是否已登录（仅判断 access token 存在性） */
export function isAuthenticated(): boolean {
  return !!getAccessToken();
}

/** 给 token 加上 Bearer 前缀 */
export function formatToken(token: string): string {
  return `Bearer ${token}`;
}
