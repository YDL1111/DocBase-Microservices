/**
 * 与后端对接的统一类型定义。
 *
 * 后端响应统一封装为 ApiResponse<T>：
 *   { success: boolean; code: string; message: string; data: T; timestamp: string }
 *
 * 与旧前端 { code: 0, msg, data } 不同，这里以 success 为主判据，code==="OK" 为成功。
 */

/** 统一响应外壳 */
export interface ApiResponse<T = unknown> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp?: string;
}

/** 登录请求：密码明文经 HTTPS 发送，后端 BCrypt 比较，不再需要前端 RSA */
export interface LoginRequest {
  username: string;
  password: string;
}

/** 刷新请求 */
export interface RefreshRequest {
  refreshToken: string;
}

/** 登录/刷新响应 */
export interface AuthResult {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  userInfo: UserInfo;
  permissions: Set<string> | string[];
}

/** 当前登录用户（非敏感字段） */
export interface UserInfo {
  userId: number;
  username: string;
  nickname: string;
  email: string;
  phoneNumber: string;
  admin: boolean;
}

/** 菜单树节点（来自 /api/auth/menus） */
export interface MenuNode {
  menuId: number;
  parentId: number | null;
  menuName: string;
  routerName: string;
  path: string;
  permission: string;
  menuType: number;
  isButton: number;
  sortNum: number;
  metaInfo: string;
  children?: MenuNode[];
}
