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

/* ============================================================
 * Knowledge 知识库相关类型
 * 对应 knowledge-service 的 Controller 与领域对象
 * ============================================================ */

/** MyBatis-Plus 分页结果（knowledge-service 使用） */
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/** 知识库实体（对应 /api/knowledge/bases） */
export interface KnowledgeBase {
  id: number;
  name: string;
  description: string;
  ownerId: number;
  visibility: number;
  status: number;
  sortNum: number;
  createdBy: number;
  updatedBy: number;
  createdAt: string;
  updatedAt: string;
}

/** 知识库创建请求 */
export interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
  visibility?: number;
}

/** 知识库更新请求 */
export interface UpdateKnowledgeBaseRequest {
  name?: string;
  description?: string;
  visibility?: number;
  status?: number;
}

/** 目录树节点（对应 /api/knowledge/bases/{kbId}/folders/tree） */
export interface FolderNode {
  id: number;
  parentId: number;
  name: string;
  sortNum: number;
  children?: FolderNode[];
}

/** 目录创建请求 */
export interface CreateFolderRequest {
  parentId?: number;
  name: string;
  sortNum?: number;
}

/** 目录更新请求 */
export interface UpdateFolderRequest {
  parentId?: number;
  name?: string;
  sortNum?: number;
}

/** 文档实体（对应 /api/knowledge/bases/{kbId}/documents） */
export interface KnowledgeDocument {
  id: number;
  knowledgeBaseId: number;
  folderId: number;
  title: string;
  originalFilename: string;
  objectKey: string;
  contentType: string;
  fileSize: number;
  checksum: string;
  ingestStatus: number;
  version: number;
  status: number;
  visibility: number;
  createdBy: number;
  updatedBy: number;
  createdAt: string;
  updatedAt: string;
}

/** 文档查询参数 */
export interface DocumentQuery {
  current?: number;
  size?: number;
  folderId?: number;
  title?: string;
  status?: number;
}

/** 成员实体（对应 /api/knowledge/bases/{kbId}/members） */
export interface KnowledgeMember {
  id: number;
  knowledgeBaseId: number;
  userId: number;
  memberRole: number;
  createdBy: number;
  createdAt: string;
}

/** 添加成员请求 */
export interface AddMemberRequest {
  userId: number;
  role: number;
}

/** 更新成员角色请求 */
export interface UpdateMemberRequest {
  role: number;
}

/** 成员角色枚举 */
export const MemberRole = {
  OWNER: 1,
  ADMIN: 2,
  EDITOR: 3,
  VIEWER: 4
} as const;

export function memberRoleLabel(role: number): string {
  switch (role) {
    case MemberRole.OWNER:
      return "所有者";
    case MemberRole.ADMIN:
      return "管理员";
    case MemberRole.EDITOR:
      return "编辑者";
    case MemberRole.VIEWER:
      return "查看者";
    default:
      return "未知";
  }
}

/** 入库状态枚举 */
export const IngestStatus = {
  PENDING: 1,
  PROCESSING: 2,
  SUCCESS: 3,
  FAILED: 4
} as const;

export function ingestStatusLabel(status: number): string {
  switch (status) {
    case IngestStatus.PENDING:
      return "待处理";
    case IngestStatus.PROCESSING:
      return "处理中";
    case IngestStatus.SUCCESS:
      return "已完成";
    case IngestStatus.FAILED:
      return "失败";
    default:
      return "未知";
  }
}

/** 文档状态枚举 */
export const DocumentStatus = {
  DRAFT: 1,
  PUBLISHED: 2,
  ARCHIVED: 3
} as const;

export function documentStatusLabel(status: number): string {
  switch (status) {
    case DocumentStatus.DRAFT:
      return "草稿";
    case DocumentStatus.PUBLISHED:
      return "已发布";
    case DocumentStatus.ARCHIVED:
      return "已归档";
    default:
      return "未知";
  }
}
