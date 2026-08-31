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
  MenuNode,
  AdminSetupRequest,
  AdminSetupStatus,
  RegisterRequest
} from "./types";

/** 获取一次性管理员初始化状态；登录页自行处理探测失败，不弹全局错误。 */
export function getAdminSetupStatus(): Promise<AdminSetupStatus> {
  return http.get<AdminSetupStatus>("/api/auth/setup", {
    skipGlobalErrorMessage: true
  });
}

/** 创建首个超级管理员。存在有效管理员后，后端永久拒绝再次初始化。 */
export function setupFirstAdmin(data: AdminSetupRequest): Promise<number> {
  return http.post<number>("/api/auth/setup", data);
}

/** 查询是否开放自助注册。探测失败由登录页静默降级为不展示入口。 */
export function getRegistrationStatus(): Promise<boolean> {
  return http.get<boolean>("/api/auth/registration", {
    skipGlobalErrorMessage: true
  });
}

/** 自助注册。服务端固定分配最小角色，客户端不得提交角色或组织。 */
export function registerApi(data: RegisterRequest): Promise<number> {
  return http.post<number>("/api/auth/register", data);
}

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
