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
