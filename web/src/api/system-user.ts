/**
 * 系统管理 - 用户管理 API 层。
 *
 * 全部走 /api/system/users/**（Gateway 路由到 iam-service）。
 * 响应由 request.ts 拦截器剥离 ApiResponse 外壳，因此泛型 T 为业务数据本身。
 *
 * 安全约束：
 *  - 所有请求经 Gateway 转发，不绕过 Gateway 直接访问内部服务；
 *  - userId 来自当前页面上下文（路由参数 / 行记录），不由用户输入拼接；
 *  - 密码仅作为请求体发送，不在 URL 中放置、不在日志/消息中拼接；
 *  - 后端返回的 SysUser 已置 password 为 null，前端不做二次处理。
 */
import { http } from "@/utils/request";
import type {
  PageResult,
  SysUser,
  SysUserQuery,
  CreateUserRequest,
  UpdateUserRequest
} from "./types";

const MAX_PAGE_SIZE = 100;

/** 校验正安全整数（与 chat.ts 同模式） */
function positiveSafeInteger(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new RangeError(`${field} must be a positive safe integer`);
  }
}

/** 校验状态值仅为 0 或 1 */
function validStatus(status: number): void {
  if (status !== 0 && status !== 1) {
    throw new RangeError("status must be 0 or 1");
  }
}

/** 获取用户列表（分页，支持 username 筛选） */
export function listUsers(query?: SysUserQuery): Promise<PageResult<SysUser>> {
  const current = query?.current ?? 1;
  const size = query?.size ?? 20;
  positiveSafeInteger(current, "current");
  positiveSafeInteger(size, "size");
  if (size > MAX_PAGE_SIZE) throw new RangeError(`size must not exceed ${MAX_PAGE_SIZE}`);
  return http.get<PageResult<SysUser>>("/api/system/users", {
    params: {
      current,
      size,
      username: query?.username || undefined
    }
  });
}

/** 获取单个用户详情 */
export function getUser(userId: number): Promise<SysUser> {
  positiveSafeInteger(userId, "userId");
  return http.get<SysUser>(`/api/system/users/${userId}`);
}

/** 创建用户，返回新建用户的 userId */
export function createUser(data: CreateUserRequest): Promise<number> {
  return http.post<number>("/api/system/users", data);
}

/** 更新用户（后端仅使用 nickname/email/phoneNumber/sex/remark/roleIds） */
export function updateUser(userId: number, data: UpdateUserRequest): Promise<void> {
  positiveSafeInteger(userId, "userId");
  return http.put<void>(`/api/system/users/${userId}`, data);
}

/** 删除用户 */
export function deleteUser(userId: number): Promise<void> {
  positiveSafeInteger(userId, "userId");
  return http.delete<void>(`/api/system/users/${userId}`);
}

/** 启停用户状态（status: 0=停用，1=启用） */
export function changeUserStatus(userId: number, status: number): Promise<void> {
  positiveSafeInteger(userId, "userId");
  validStatus(status);
  return http.put<void>(`/api/system/users/${userId}/status`, { status });
}

/** 重置密码 */
export function resetPassword(userId: number, password: string): Promise<void> {
  positiveSafeInteger(userId, "userId");
  return http.put<void>(`/api/system/users/${userId}/password`, { password });
}

/** 获取用户已分配的角色 ID 列表 */
export function getUserRoles(userId: number): Promise<number[]> {
  positiveSafeInteger(userId, "userId");
  return http.get<number[]>(`/api/system/users/${userId}/roles`);
}
