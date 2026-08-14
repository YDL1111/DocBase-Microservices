/**
 * 系统管理 - 菜单管理 API 层。
 *
 * 全部走 /api/system/menus/**（Gateway 路由到 iam-service），
 * 响应由 request.ts 拦截器剥离 ApiResponse 外壳，泛型 T 为业务数据本身。
 *
 * 安全约束（与 role.ts / system-user.ts 同模式）：
 *  - 所有请求经 Gateway 转发，不拼接 X-User-* 头，不读取/保存 JWT 内容；
 *  - menuId/parentId/menuType/status/isButton/sortNum 发请求前严格校验，
 *    非法值一律不发请求；
 *  - 提交请求体只含后端 DTO 字段，绝不携带 isSystem / deleted / creatorId /
 *    createTime / updateTime 等服务端字段；
 *  - update 不携带 status：启停只能调用 PUT /{menuId}/status 专用接口；
 *  - 菜单 owner 与角色菜单权限严格分离：owner 仅走 /owners，绝不写 sys_role_menu。
 */
import { http } from "@/utils/request";
import type {
  SysMenu,
  MenuNode,
  CreateMenuRequest,
  UpdateMenuRequest,
  ChangeMenuStatusRequest
} from "./types";

/** 与后端 CreateMenuRequest/UpdateMenuRequest 长度限制一致 */
export const MENU_NAME_MAX = 64;
export const ROUTER_NAME_MAX = 128;
export const PATH_MAX = 255;
export const PERMISSION_MAX = 128;
export const META_INFO_MAX = 1024;
export const REMARK_MAX = 512;
export const SORT_NUM_MAX = 9999;
/** 菜单有效 Owner 最多 100 个（与后端 MenuOwnerRolesRequest 一致） */
export const MAX_MENU_OWNERS = 100;

/** routerName 正则（与 MenuService MENU_ROUTER_INVALID 规则一致） */
export const ROUTER_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{0,127}$/;
/** path 正则（与 MenuService MENU_PATH_INVALID 规则一致） */
export const PATH_PATTERN = /^(\/[A-Za-z0-9_-]+)+$/;
/** permission 正则（与 MenuService MENU_PERMISSION_INVALID 规则一致） */
export const PERMISSION_PATTERN = /^[a-z0-9:._-]{1,128}$/;

/** 校验正安全整数（menuId 等路径参数必须 ≥ 1） */
function positiveSafeInteger(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new RangeError(`${field} must be a positive safe integer`);
  }
}

/**
 * 校验 Owner 全量替换的 roleIds。空数组是有效且有意义的“系统托管”，
 * 因此调用方必须显式传入数组，不能将 null/undefined 静默转为空数组。
 */
function sanitizeOwnerRoleIds(roleIds: number[]): number[] {
  if (!Array.isArray(roleIds)) {
    throw new TypeError("roleIds must be an array");
  }
  const seen = new Set<number>();
  const deduped: number[] = [];
  for (const roleId of roleIds) {
    if (!Number.isSafeInteger(roleId) || roleId < 1) {
      throw new RangeError("roleIds must contain only positive safe integers");
    }
    if (!seen.has(roleId)) {
      seen.add(roleId);
      deduped.push(roleId);
    }
  }
  if (deduped.length > MAX_MENU_OWNERS) {
    throw new RangeError(`roleIds must not exceed ${MAX_MENU_OWNERS} entries`);
  }
  return deduped;
}

/** 校验 parentId：根节点为 0，其余为正安全整数 */
function validParentId(parentId: number): void {
  if (!Number.isSafeInteger(parentId) || parentId < 0) {
    throw new RangeError("parentId must be a non-negative safe integer");
  }
}

/** 校验 menuType 仅为 1/2/3（1=菜单 2=目录 3=按钮） */
function validMenuType(menuType: number): void {
  if (menuType !== 1 && menuType !== 2 && menuType !== 3) {
    throw new RangeError("menuType must be 1 (menu), 2 (directory) or 3 (button)");
  }
}

/** 校验状态值仅为 0 或 1 */
function validStatus(status: number): void {
  if (status !== 0 && status !== 1) {
    throw new RangeError("status must be 0 or 1");
  }
}

/** 校验 isButton 仅为 0 或 1 */
function validIsButton(isButton: number): void {
  if (isButton !== 0 && isButton !== 1) {
    throw new RangeError("isButton must be 0 or 1");
  }
}

/** 校验 sortNum：非负且不超过后端上限 9999 */
function validSortNum(sortNum: number): void {
  if (!Number.isSafeInteger(sortNum) || sortNum < 0 || sortNum > SORT_NUM_MAX) {
    throw new RangeError(`sortNum must be an integer between 0 and ${SORT_NUM_MAX}`);
  }
}

/** 校验长度上限（与后端 @Size 一致） */
function validMaxLen(value: string | undefined, max: number, field: string): void {
  if (value != null && value.length > max) {
    throw new RangeError(`${field} must not exceed ${max} characters`);
  }
}

/**
 * 严格校验写请求字段（create/update 共用）：
 *  - parentId ≥ 0 整数；menuType ∈ {1,2,3}；isButton ∈ {0,1}；sortNum ∈ [0,9999]；
 *  - menuName/remark/metaInfo 长度与后端 DTO 一致；
 *  - 节点类型不变量与后端 MenuService.validateMenuInput 对齐：
 *      目录/菜单(1/2)：routerName/path 必填且匹配正则、isButton=0；
 *      按钮(3)：permission 必填、routerName/path 必须为空、isButton=1。
 * 非法输入直接抛错，绝不发请求。
 */
function validateWriteRequest(body: {
  menuName: string;
  menuType: number;
  isButton?: number;
  sortNum?: number;
  parentId: number;
  routerName?: string;
  path?: string;
  permission?: string;
  metaInfo?: string;
  remark?: string;
}): void {
  validParentId(body.parentId);
  validMenuType(body.menuType);
  if (body.isButton != null) validIsButton(body.isButton);
  if (body.sortNum != null) validSortNum(body.sortNum);
  if (body.menuName == null || body.menuName.trim() === "") {
    throw new RangeError("menuName must not be blank");
  }
  validMaxLen(body.menuName, MENU_NAME_MAX, "menuName");
  validMaxLen(body.routerName, ROUTER_NAME_MAX, "routerName");
  validMaxLen(body.path, PATH_MAX, "path");
  validMaxLen(body.permission, PERMISSION_MAX, "permission");
  validMaxLen(body.metaInfo, META_INFO_MAX, "metaInfo");
  validMaxLen(body.remark, REMARK_MAX, "remark");

  const isButton = body.isButton ?? 0;
  if (body.menuType === 3) {
    // 按钮：permission 必填；routerName/path 必须为空；isButton 固定 1
    if (body.permission == null || body.permission.trim() === "") {
      throw new RangeError("button menu must have a non-empty permission");
    }
    if (body.routerName != null && body.routerName.trim() !== "") {
      throw new RangeError("button menu must not have a routerName");
    }
    if (body.path != null && body.path.trim() !== "") {
      throw new RangeError("button menu must not have a path");
    }
    if (isButton !== 1) {
      throw new RangeError("button menu must have isButton=1");
    }
  } else {
    // 目录/菜单：routerName/path 必填且匹配正则；isButton 固定 0
    if (body.routerName == null || body.routerName.trim() === "") {
      throw new RangeError("menu or directory must have a non-empty routerName");
    }
    if (!ROUTER_NAME_PATTERN.test(body.routerName.trim())) {
      throw new RangeError("routerName must start with a letter and contain only letters, digits, underscore or hyphen");
    }
    if (body.path == null || body.path.trim() === "") {
      throw new RangeError("menu or directory must have a non-empty path");
    }
    if (!PATH_PATTERN.test(body.path.trim())) {
      throw new RangeError("path must start with '/' and contain only letters, digits, underscore or hyphen segments");
    }
    if (isButton !== 0) {
      throw new RangeError("non-button menu must have isButton=0");
    }
  }
  // permission 非空时格式校验（与后端 MENU_PERMISSION_INVALID 一致）
  if (body.permission != null && body.permission.trim() !== "") {
    if (!PERMISSION_PATTERN.test(body.permission.trim())) {
      throw new RangeError("permission must contain only lowercase letters, digits, colon, dot, underscore or hyphen");
    }
  }
  // metaInfo 非空时必须为合法 JSON 对象（与后端 MENU_METAINFO_INVALID 一致）
  if (body.metaInfo != null && body.metaInfo.trim() !== "") {
    const trimmed = body.metaInfo.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      throw new RangeError("metaInfo must be a valid JSON object");
    }
    try {
      const parsed = JSON.parse(trimmed);
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        throw new RangeError("metaInfo must be a valid JSON object");
      }
    } catch (e) {
      if (e instanceof RangeError) throw e;
      throw new RangeError("metaInfo must be a valid JSON object");
    }
  }
}

/** 获取菜单列表（一维数组，按 parent_id/sort_num 排序） */
export function listMenus(): Promise<SysMenu[]> {
  return http.get<SysMenu[]>("/api/system/menus");
}

/** 获取全量菜单树（树形，含 status/isSystem） */
export function listMenuTree(): Promise<MenuNode[]> {
  return http.get<MenuNode[]>("/api/system/menus/tree");
}

/** 获取单个菜单详情 */
export function getMenu(menuId: number): Promise<SysMenu> {
  positiveSafeInteger(menuId, "menuId");
  return http.get<SysMenu>(`/api/system/menus/${menuId}`);
}

/** 获取菜单当前有效 Owner 角色 ID，仅 admin:all 可调用。 */
export function getMenuOwners(menuId: number): Promise<number[]> {
  positiveSafeInteger(menuId, "menuId");
  return http.get<number[]>(`/api/system/menus/${menuId}/owners`);
}

/**
 * 全量替换菜单 Owner，仅 admin:all 可调用。
 * 此接口只写 sys_menu_owner_role；[] 表示系统托管，不会授予任何菜单 permission。
 */
export function replaceMenuOwners(menuId: number, roleIds: number[]): Promise<void> {
  positiveSafeInteger(menuId, "menuId");
  return http.put<void>(`/api/system/menus/${menuId}/owners`, {
    roleIds: sanitizeOwnerRoleIds(roleIds)
  });
}

/** 创建菜单，返回新建的 menuId */
export function createMenu(data: CreateMenuRequest): Promise<number> {
  validateWriteRequest(data);
  if (data.status != null) validStatus(data.status);
  const body = {
    parentId: data.parentId,
    menuName: data.menuName.trim(),
    menuType: data.menuType,
    routerName: data.routerName != null && data.routerName.trim() !== "" ? data.routerName.trim() : null,
    path: data.path != null && data.path.trim() !== "" ? data.path.trim() : null,
    permission: data.permission != null && data.permission.trim() !== "" ? data.permission.trim() : null,
    metaInfo: data.metaInfo != null && data.metaInfo.trim() !== "" ? data.metaInfo.trim() : null,
    isButton: data.isButton ?? 0,
    sortNum: data.sortNum ?? 0,
    status: data.status ?? 1,
    remark: data.remark != null && data.remark.trim() !== "" ? data.remark.trim() : null
  };
  return http.post<number>("/api/system/menus", body);
}

/**
 * 更新菜单。
 *
 * 关键安全约束：
 *  - 请求体只含 UpdateMenuRequest 字段：绝不含 status（启停走专用接口）、
 *    isSystem、deleted、creatorId、createTime 等；
 *  - isButton 由 menuType 推导（目录/菜单=0，按钮=1），客户端不可伪造。
 */
export function updateMenu(menuId: number, data: UpdateMenuRequest): Promise<void> {
  positiveSafeInteger(menuId, "menuId");
  validateWriteRequest(data);
  const body = {
    parentId: data.parentId,
    menuName: data.menuName.trim(),
    menuType: data.menuType,
    routerName: data.routerName != null && data.routerName.trim() !== "" ? data.routerName.trim() : null,
    path: data.path != null && data.path.trim() !== "" ? data.path.trim() : null,
    permission: data.permission != null && data.permission.trim() !== "" ? data.permission.trim() : null,
    metaInfo: data.metaInfo != null && data.metaInfo.trim() !== "" ? data.metaInfo.trim() : null,
    isButton: data.isButton,
    sortNum: data.sortNum,
    remark: data.remark != null && data.remark.trim() !== "" ? data.remark.trim() : null
  };
  return http.put<void>(`/api/system/menus/${menuId}`, body);
}

/** 启停菜单（status: 0=停用，1=启用），走专用状态接口 */
export function changeMenuStatus(menuId: number, status: number): Promise<void> {
  positiveSafeInteger(menuId, "menuId");
  validStatus(status);
  const body: ChangeMenuStatusRequest = { status };
  return http.put<void>(`/api/system/menus/${menuId}/status`, body);
}

/** 删除菜单（有子节点/系统保留时后端返回业务错误） */
export function deleteMenu(menuId: number): Promise<void> {
  positiveSafeInteger(menuId, "menuId");
  return http.delete<void>(`/api/system/menus/${menuId}`);
}
