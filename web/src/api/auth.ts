/**
 * IAM 认证相关 API。全部走 /api/auth/*（Gateway 路由到 iam-service）。
 */
import { http } from "@/utils/request";
import type {
  ApiResponse,
  LoginRequest,
  RefreshRequest,
  AuthResult,
  UserInfo,
  MenuNode
} from "./types";

/**
 * 注意：http 层响应拦截器会剥离 ApiResponse 外壳，直接返回 data 字段，
 * 因此这里泛型 T 是业务数据本身（如 AuthResult），而非 ApiResponse<T>。
 */

/** 登录：POST /api/auth/login */
export function loginApi(data: LoginRequest): Promise<AuthResult> {
  return http.post<AuthResult>("/api/auth/login", data);
}

/** 刷新 token：POST /api/auth/refresh */
export function refreshApi(data: RefreshRequest): Promise<AuthResult> {
  return http.post<AuthResult>("/api/auth/refresh", data);
}

/** 登出：POST /api/auth/logout */
export function logoutApi(data: RefreshRequest): Promise<void> {
  return http.post<void>("/api/auth/logout", data);
}

/** 获取当前用户：GET /api/auth/me */
export function getMeApi(): Promise<UserInfo> {
  return http.get<UserInfo>("/api/auth/me");
}

/** 获取当前用户权限码：GET /api/auth/permissions */
export function getPermissionsApi(): Promise<string[]> {
  return http.get<string[]>("/api/auth/permissions");
}

/** 获取当前用户菜单树：GET /api/auth/menus */
export function getMenusApi(): Promise<MenuNode[]> {
  return http.get<MenuNode[]>("/api/auth/menus");
}
