/**
 * 系统管理 - 角色管理 API 层。
 *
 * 全部走 /api/system/roles/**（Gateway 路由到 iam-service）。
 * 响应由 request.ts 拦截器剥离 ApiResponse 外壳，因此泛型 T 为业务数据本身。
 *
 * 安全约束：
 *  - 所有请求经 Gateway 转发，不绕过 Gateway 直接访问内部服务；
 *  - roleId/menuId 在发请求前校验为正安全整数，非法一律不发请求；
 *  - 不拼接 X-User-* 头，不读取/保存 JWT 内容；
 *  - 提交角色时不携带 isSystem / deleted / creatorId 等服务端字段；
 *  - 菜单授权使用"全量菜单树"(/api/system/menus/tree)，不使用调用者可见菜单，
 *    避免静默覆盖调用者不可见的既有授权；后端负责权限子集校验。
 */
import { http } from "@/utils/request";
import type {
  PageResult,
  SysRole,
  SysRoleQuery,
  CreateRoleRequest,
  UpdateRoleRequest,
  AssignRoleMenusRequest,
  MenuNode
} from "./types";

const MAX_PAGE_SIZE = 100;
/** 角色菜单最多 500 项（与后端 DTO @Size(max=500) 一致） */
export const MAX_ROLE_MENUS = 500;

/** 校验正安全整数（与 chat.ts / system-user.ts 同模式） */
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

/** 过滤并校验菜单 ID 集合：仅保留正安全整数、去重、上限 500。返回 null 表示"不修改"。 */
function sanitizeMenuIds(menuIds: number[] | null | undefined): number[] | null {
  if (menuIds == null) return null; // null = 不修改菜单
  const deduped: number[] = [];
  const seen = new Set<number>();
  for (const id of menuIds) {
    if (!Number.isSafeInteger(id) || id < 1) {
      throw new RangeError("menuIds must contain only positive safe integers");
    }
    if (!seen.has(id)) {
      seen.add(id);
      deduped.push(id);
    }
  }
  if (deduped.length > MAX_ROLE_MENUS) {
    throw new RangeError(`menuIds must not exceed ${MAX_ROLE_MENUS} entries`);
  }
  return deduped;
}

/** 获取角色列表（分页，支持 roleName 筛选） */
export function listRoles(query?: SysRoleQuery): Promise<PageResult<SysRole>> {
  const current = query?.current ?? 1;
  const size = query?.size ?? 20;
  positiveSafeInteger(current, "current");
  positiveSafeInteger(size, "size");
  if (size > MAX_PAGE_SIZE) throw new RangeError(`size must not exceed ${MAX_PAGE_SIZE}`);
  return http.get<PageResult<SysRole>>("/api/system/roles", {
    params: {
      current,
      size,
      roleName: query?.roleName || undefined
    }
  });
}

/** 获取全部角色（不分页，用于下拉等场景） */
export function listAllRoles(): Promise<SysRole[]> {
  return http.get<SysRole[]>("/api/system/roles/all");
}

/** 获取单个角色详情 */
export function getRole(roleId: number): Promise<SysRole> {
  positiveSafeInteger(roleId, "roleId");
  return http.get<SysRole>(`/api/system/roles/${roleId}`);
}

/** 创建角色，返回新建角色的 roleId */
export function createRole(data: CreateRoleRequest): Promise<number> {
  const body = {
    roleName: data.roleName,
    roleKey: data.roleKey,
    roleSort: data.roleSort,
    dataScope: data.dataScope,
    status: data.status,
    remark: data.remark,
    menuIds: sanitizeMenuIds(data.menuIds)
  };
  return http.post<number>("/api/system/roles", body);
}

/** 更新角色基本信息与菜单分配（menuIds=null 表示不修改菜单） */
export function updateRole(roleId: number, data: UpdateRoleRequest): Promise<void> {
  positiveSafeInteger(roleId, "roleId");
  const body = {
    roleName: data.roleName,
    roleKey: data.roleKey,
    roleSort: data.roleSort,
    dataScope: data.dataScope,
    remark: data.remark,
    menuIds: sanitizeMenuIds(data.menuIds)
  };
  return http.put<void>(`/api/system/roles/${roleId}`, body);
}

/** 删除角色 */
export function deleteRole(roleId: number): Promise<void> {
  positiveSafeInteger(roleId, "roleId");
  return http.delete<void>(`/api/system/roles/${roleId}`);
}

/** 启停角色（status: 0=停用，1=启用） */
export function changeRoleStatus(roleId: number, status: number): Promise<void> {
  positiveSafeInteger(roleId, "roleId");
  validStatus(status);
  return http.put<void>(`/api/system/roles/${roleId}/status`, { status });
}

/** 获取角色已分配的菜单 ID 列表 */
export function getRoleMenuIds(roleId: number): Promise<number[]> {
  positiveSafeInteger(roleId, "roleId");
  return http.get<number[]>(`/api/system/roles/${roleId}/menus`);
}

/** 全量替换角色的菜单权限（空数组 = 清空） */
export function assignRoleMenus(roleId: number, data: AssignRoleMenusRequest): Promise<void> {
  positiveSafeInteger(roleId, "roleId");
  const menuIds = sanitizeMenuIds(data.menuIds);
  // menuIds 不会为 null：全量替换语义下 [] 表示清空，调用方必须显式传数组
  return http.put<void>(`/api/system/roles/${roleId}/menus`, { menuIds: menuIds ?? [] });
}

/**
 * 获取全量菜单树（用于角色菜单授权）。
 * 使用 /api/system/menus/tree 而非调用者可见菜单，确保不会遗漏调用者不可见的既有授权。
 * 需要 system:menu:list 或 admin:all 权限。
 */
export function listMenuTree(): Promise<MenuNode[]> {
  return http.get<MenuNode[]>("/api/system/menus/tree");
}