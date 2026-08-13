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

/**
 * 菜单树节点。
 *
 * 同时来自两个接口（字段一致，后端同用 MenuService.MenuNode record）：
 *  - /api/auth/menus（当前用户可见菜单树）
 *  - /api/system/menus/tree（全量菜单树，用于菜单管理与角色授权）
 */
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
  /** 1=启用 0=停用（菜单管理 tree 接口返回） */
  status?: number;
  /** 1=系统保留 0=普通菜单。安全属性只认后端该字段，不凭名称/路由推断。 */
  isSystem?: number;
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

/* ============================================================
 * AI Chat session history (Phase 4A)
 * ============================================================ */

export interface ChatSession {
  id: number;
  userId: number;
  knowledgeBaseId: number | null;
  title: string;
  status: number;
  createdAt: string;
  updatedAt: string;
  deleted?: number;
}

export interface CreateChatSessionRequest {
  knowledgeBaseId: number | null;
  title: string;
}

export interface ChatMessage {
  id: number;
  sessionId: number;
  userId: number;
  role: number;
  content: string;
  status: number;
  clientRequestId?: string | null;
  sourcesJson?: string | null;
  errorCode?: string | null;
  createdAt: string;
  completedAt?: string | null;
  deleted?: number;
}

export const ChatSessionStatus = { ACTIVE: 1, ARCHIVED: 2 } as const;
export const ChatMessageRole = { USER: 1, ASSISTANT: 2, SYSTEM: 3 } as const;
export const ChatMessageStatus = { STREAMING: 1, COMPLETED: 2, FAILED: 3, CANCELLED: 4 } as const;

export function chatMessageRoleLabel(role: number): string {
  return role === ChatMessageRole.USER ? "用户" : role === ChatMessageRole.ASSISTANT ? "助手" : role === ChatMessageRole.SYSTEM ? "系统" : "未知角色";
}

export function chatMessageStatusLabel(status: number): string {
  return status === ChatMessageStatus.STREAMING ? "生成中" : status === ChatMessageStatus.COMPLETED ? "已完成" : status === ChatMessageStatus.FAILED ? "失败" : status === ChatMessageStatus.CANCELLED ? "已取消" : "未知状态";
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

/** Browser-to-Gateway multipart upload request. Object storage details never leave the server. */
export interface UploadDocumentRequest {
  file: File;
  clientRequestId: string;
  title?: string;
  folderId?: number;
  visibility?: number;
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

/* ============================================================
 * Ingest 导入任务相关类型
 * 对应 ingest-service 的 IngestTaskController 与 IngestTask 实体
 * ============================================================ */

/** 导入任务实体（对应 /api/knowledge/bases 和 /api/ingest/tasks） */
export interface IngestTask {
  id: number;
  eventId: string;
  knowledgeBaseId: number;
  documentId: number;
  versionId: number;
  objectKey: string;
  fileName: string;
  contentType: string;
  taskType: string;
  status: string;
  attemptCount: number;
  lastError: string;
  nextRetryAt: string;
  pythonKbId: string;
  pythonDocId: string;
  chunkCount: number;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
  startedAt: string;
  finishedAt: string;
}

/** 任务列表查询参数（仅支持 current/size/status） */
export interface IngestTaskQuery {
  current?: number;
  size?: number;
  status?: string;
}

/** 任务状态枚举 */
export const IngestTaskStatusEnum = {
  PENDING: "PENDING",
  PROCESSING: "PROCESSING",
  DISPATCHED: "DISPATCHED",
  SUCCEEDED: "SUCCEEDED",
  FAILED: "FAILED",
  RETRY_WAIT: "RETRY_WAIT",
  DEAD: "DEAD",
  CANCELLED: "CANCELLED"
} as const;

/** 需要轮询的活跃状态 */
export const ACTIVE_STATUSES: string[] = [
  IngestTaskStatusEnum.PENDING,
  IngestTaskStatusEnum.PROCESSING,
  IngestTaskStatusEnum.DISPATCHED,
  IngestTaskStatusEnum.RETRY_WAIT
];

/** 终态状态 */
export const TERMINAL_STATUSES: string[] = [
  IngestTaskStatusEnum.SUCCEEDED,
  IngestTaskStatusEnum.FAILED,
  IngestTaskStatusEnum.DEAD,
  IngestTaskStatusEnum.CANCELLED
];

/** 可重试状态 */
export const RETRYABLE_STATUSES: string[] = [
  IngestTaskStatusEnum.FAILED,
  IngestTaskStatusEnum.DEAD
];

/** 可取消状态 */
export const CANCELABLE_STATUSES: string[] = [
  IngestTaskStatusEnum.PENDING,
  IngestTaskStatusEnum.RETRY_WAIT
];

/** 任务状态标签（用于 IngestTask 字符串状态） */
export function ingestTaskStatusLabel(status: string): string {
  switch (status) {
    case IngestTaskStatusEnum.PENDING:
      return "待处理";
    case IngestTaskStatusEnum.PROCESSING:
      return "处理中";
    case IngestTaskStatusEnum.DISPATCHED:
      return "已分发";
    case IngestTaskStatusEnum.SUCCEEDED:
      return "已完成";
    case IngestTaskStatusEnum.FAILED:
      return "失败";
    case IngestTaskStatusEnum.RETRY_WAIT:
      return "等待重试";
    case IngestTaskStatusEnum.DEAD:
      return "永久失败";
    case IngestTaskStatusEnum.CANCELLED:
      return "已取消";
    default:
      return "未知";
  }
}

/** 任务状态标签类型（用于 Element Plus Tag 组件） */
export function ingestTaskStatusTagType(
  status: string
): "primary" | "success" | "info" | "warning" | "danger" | "" {
  switch (status) {
    case IngestTaskStatusEnum.PENDING:
      return "info";
    case IngestTaskStatusEnum.PROCESSING:
    case IngestTaskStatusEnum.DISPATCHED:
      return "primary";
    case IngestTaskStatusEnum.SUCCEEDED:
      return "success";
    case IngestTaskStatusEnum.FAILED:
    case IngestTaskStatusEnum.RETRY_WAIT:
      return "warning";
    case IngestTaskStatusEnum.DEAD:
    case IngestTaskStatusEnum.CANCELLED:
      return "danger";
    default:
      return "";
  }
}

/** 任务类型标签 */
export function ingestTaskTypeLabel(taskType: string): string {
  switch (taskType) {
    case "IMPORT":
      return "导入";
    case "REIMPORT":
      return "重新导入";
    case "RETRY":
      return "重试";
    case "DELETE":
      return "删除";
    default:
      return "未知";
  }
}

/** 判断是否为活跃状态（需要轮询） */
export function isActiveStatus(status: string): boolean {
  return ACTIVE_STATUSES.includes(status);
}

/** 判断是否为终态（停止轮询） */
export function isTerminalStatus(status: string): boolean {
  return TERMINAL_STATUSES.includes(status);
}

/** 判断是否可重试 */
export function isRetryableStatus(status: string): boolean {
  return RETRYABLE_STATUSES.includes(status);
}

/** 判断是否可取消 */
export function isCancelableStatus(status: string): boolean {
  return CANCELABLE_STATUSES.includes(status);
}

/* ============================================================
 * 系统管理 - 用户管理相关类型
 * 对应 iam-service 的 UserController 与 SysUser 实体
 * ============================================================ */

/** 用户实体（对应 /api/system/users），password 返回前由后端置 null */
export interface SysUser {
  userId: number;
  username: string;
  nickname: string;
  email: string;
  phoneNumber: string;
  /** 0未知 1男 2女 */
  sex: number;
  /** 1启用 0停用 */
  status: number;
  remark: string;
  isAdmin?: number;
  loginDate?: string;
  createTime: string;
  updateTime: string;
}

/** 用户列表查询参数（仅支持 current/size/username 筛选） */
export interface SysUserQuery {
  current?: number;
  size?: number;
  username?: string;
}

/** 创建用户请求（username/password 必填） */
export interface CreateUserRequest {
  username: string;
  password: string;
  nickname?: string;
  email?: string;
  phoneNumber?: string;
  sex?: number;
  status?: number;
  remark?: string;
  roleIds?: number[];
}

/**
 * 更新用户请求。
 * 注意：后端 PUT 仅使用 nickname/email/phoneNumber/sex/remark/roleIds，
 * username/password/status 虽可传但会被忽略。
 */
export interface UpdateUserRequest {
  nickname?: string;
  email?: string;
  phoneNumber?: string;
  sex?: number;
  remark?: string;
  roleIds?: number[];
}

/** 用户状态枚举 */
export const UserStatus = { ENABLED: 1, DISABLED: 0 } as const;

export function userStatusLabel(status: number): string {
  return status === UserStatus.ENABLED ? "启用" : status === UserStatus.DISABLED ? "停用" : "未知";
}

/** 用户状态标签类型（用于 Element Plus Tag） */
export function userStatusTagType(status: number): "success" | "info" | "" {
  return status === UserStatus.ENABLED ? "success" : status === UserStatus.DISABLED ? "info" : "";
}

/* ============================================================
 * 系统管理 - 角色管理相关类型
 * 对应 iam-service 的 RoleController / SysRole 实体 / 角色 DTO
 * ============================================================ */

/** 角色实体（对应 /api/system/roles） */
export interface SysRole {
  roleId: number;
  roleName: string;
  roleKey: string;
  roleSort: number;
  /** 数据范围：1全部 2自定义 3本部门 4本部门及以下 5本人 */
  dataScope: number;
  /** 1启用 0停用 */
  status: number;
  /** 1=系统保留角色（仅超级管理员可修改/停用/删除/分配） */
  isSystem: number;
  remark: string;
  createTime: string;
  updateTime: string;
}

/** 角色列表查询参数（仅支持 current/size/roleName 筛选） */
export interface SysRoleQuery {
  current?: number;
  size?: number;
  roleName?: string;
}

/** 创建角色请求（roleName/roleKey 必填） */
export interface CreateRoleRequest {
  roleName: string;
  roleKey: string;
  roleSort?: number;
  dataScope?: number;
  status?: number;
  remark?: string;
  menuIds?: number[] | null;
}

/**
 * 更新角色请求。
 * 关键语义：menuIds=null 表示"不修改菜单"；menuIds=[] 表示"清空菜单"。
 * 不允许提交 isSystem / deleted / creatorId 等服务端字段。
 */
export interface UpdateRoleRequest {
  roleName: string;
  roleKey: string;
  roleSort?: number;
  dataScope?: number;
  remark?: string;
  /** null = 不修改菜单；[] = 清空菜单 */
  menuIds?: number[] | null;
}

/** 角色状态枚举 */
export const RoleStatus = { ENABLED: 1, DISABLED: 0 } as const;

export function roleStatusLabel(status: number): string {
  return status === RoleStatus.ENABLED ? "启用" : status === RoleStatus.DISABLED ? "停用" : "未知";
}

export function roleStatusTagType(status: number): "success" | "info" | "" {
  return status === RoleStatus.ENABLED ? "success" : status === RoleStatus.DISABLED ? "info" : "";
}

/** 数据范围枚举（与 sys_role.data_scope 一致） */
export const DataScope = {
  ALL: 1,
  CUSTOM: 2,
  DEPT: 3,
  DEPT_AND_BELOW: 4,
  SELF: 5
} as const;

/** 数据范围选项（用于下拉框） */
export const DATA_SCOPE_OPTIONS: { value: number; label: string }[] = [
  { value: DataScope.ALL, label: "全部数据" },
  { value: DataScope.CUSTOM, label: "自定义" },
  { value: DataScope.DEPT, label: "本部门" },
  { value: DataScope.DEPT_AND_BELOW, label: "本部门及以下" },
  { value: DataScope.SELF, label: "仅本人" }
];

export function dataScopeLabel(scope: number): string {
  const found = DATA_SCOPE_OPTIONS.find(o => o.value === scope);
  return found ? found.label : "未知";
}

/** 分配菜单请求体 */
export interface AssignRoleMenusRequest {
  menuIds: number[];
}

/* ============================================================
 * 系统管理 - 菜单管理相关类型
 * 对应 iam-service 的 MenuController、SysMenu 实体与菜单 DTO
 * ============================================================ */

/** 菜单类型：1=菜单 2=目录 3=按钮 */
export const MenuType = { MENU: 1, DIRECTORY: 2, BUTTON: 3 } as const;

/** 菜单状态：0=停用 1=启用 */
export const MenuStatus = { DISABLED: 0, ENABLED: 1 } as const;

/** 菜单实体（对应 /api/system/menus 列表与 /api/system/menus/{menuId} 详情） */
export interface SysMenu {
  menuId: number;
  parentId: number;
  menuName: string;
  /** 1=菜单 2=目录 3=按钮 */
  menuType: number;
  routerName: string;
  path: string;
  permission: string;
  metaInfo: string;
  /** 0=非按钮 1=按钮（由 menuType 推导，客户端不可伪造） */
  isButton: number;
  sortNum: number;
  /** 1=系统保留（仅后端 Flyway 写入），0=普通菜单 */
  isSystem: number;
  /** 1=启用 0=停用 */
  status: number;
  remark: string;
  creatorId: number;
  createTime: string;
  updaterId: number;
  updateTime: string;
}

/**
 * 创建菜单请求。
 *
 * 与后端 CreateMenuRequest 一致：
 *  - parentId 根节点为 0；
 *  - status 仅创建时可指定（0/1，默认 1）；
 *  - isButton / sortNum 后端可空，前端始终显式推导提交；
 *  - 绝不携带 menuId / isSystem / deleted / 审计字段（不在 DTO 中）。
 */
export interface CreateMenuRequest {
  parentId: number;
  menuName: string;
  menuType: number;
  routerName?: string;
  path?: string;
  permission?: string;
  metaInfo?: string;
  isButton?: number;
  sortNum?: number;
  status?: number;
  remark?: string;
}

/**
 * 更新菜单请求。
 *
 * 与后端 UpdateMenuRequest 一致：
 *  - 不包含 status——状态变更只能走 PUT /{menuId}/status 专用接口；
 *  - 不包含 isSystem / deleted / creatorId 等服务端字段；
 *  - isButton / sortNum 后端 @NotNull，前端必须提交。
 */
export interface UpdateMenuRequest {
  parentId: number;
  menuName: string;
  menuType: number;
  routerName?: string;
  path?: string;
  permission?: string;
  metaInfo?: string;
  isButton: number;
  sortNum: number;
  remark?: string;
}

/** 启停菜单请求（仅 status，0=停用 1=启用） */
export interface ChangeMenuStatusRequest {
  status: number;
}

/** 菜单类型中文标签 */
export function menuTypeLabel(menuType: number): string {
  switch (menuType) {
    case MenuType.MENU:
      return "菜单";
    case MenuType.DIRECTORY:
      return "目录";
    case MenuType.BUTTON:
      return "按钮";
    default:
      return "未知";
  }
}

/** 菜单类型标签类型（用于 Element Plus Tag） */
export function menuTypeTagType(menuType: number): "primary" | "success" | "warning" | "info" | "" {
  switch (menuType) {
    case MenuType.MENU:
      return "primary";
    case MenuType.DIRECTORY:
      return "success";
    case MenuType.BUTTON:
      return "warning";
    default:
      return "info";
  }
}

/** 菜单状态标签 */
export function menuStatusLabel(status: number): string {
  return status === MenuStatus.ENABLED ? "启用" : status === MenuStatus.DISABLED ? "停用" : "未知";
}

export function menuStatusTagType(status: number): "success" | "info" | "" {
  return status === MenuStatus.ENABLED ? "success" : status === MenuStatus.DISABLED ? "info" : "";
}

/** 是否按钮节点（安全属性只认后端 isButton/isSystem，不凭名称或路由推断） */
export function isButtonNode(menu: { isButton?: number }): boolean {
  return menu.isButton === 1;
}
