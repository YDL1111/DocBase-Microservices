/**
 * Knowledge 知识库 API 层。
 *
 * 全部走 /api/knowledge/**（Gateway 路由到 knowledge-service）。
 * 响应由 request.ts 拦截器剥离 ApiResponse 外壳，因此泛型 T 为业务数据本身。
 *
 * 安全约束：
 *  - 所有请求经 Gateway 转发，不绕过 Gateway 直接访问内部服务；
 *  - knowledgeBaseId 来自当前页面上下文（路由参数），不由用户输入拼接；
 *  - 不在请求 URL 中放置 Token 或敏感信息。
 */
import { http } from "@/utils/request";
import type {
  PageResult,
  KnowledgeBase,
  CreateKnowledgeBaseRequest,
  UpdateKnowledgeBaseRequest,
  FolderNode,
  CreateFolderRequest,
  UpdateFolderRequest,
  KnowledgeDocument,
  DocumentQuery,
  UploadDocumentRequest,
  UpdateDocumentRequest,
  KnowledgeMember,
  AddMemberRequest,
  UpdateMemberRequest
} from "./types";

/* ========================= 知识库 ========================= */

/** 获取当前用户有权限的知识库列表（分页） */
export function listKnowledgeBases(params?: {
  current?: number;
  size?: number;
}): Promise<PageResult<KnowledgeBase>> {
  return http.get<PageResult<KnowledgeBase>>("/api/knowledge/bases", {
    params: {
      current: params?.current ?? 1,
      size: params?.size ?? 20
    }
  });
}

/** 获取单个知识库详情 */
export function getKnowledgeBase(
  knowledgeBaseId: number
): Promise<KnowledgeBase> {
  return http.get<KnowledgeBase>(`/api/knowledge/bases/${knowledgeBaseId}`);
}

/** 创建知识库 */
export function createKnowledgeBase(
  data: CreateKnowledgeBaseRequest
): Promise<number> {
  return http.post<number>("/api/knowledge/bases", data);
}

/** 更新知识库 */
export function updateKnowledgeBase(
  knowledgeBaseId: number,
  data: UpdateKnowledgeBaseRequest
): Promise<void> {
  return http.put<void>(`/api/knowledge/bases/${knowledgeBaseId}`, data);
}

/** 删除知识库 */
export function deleteKnowledgeBase(knowledgeBaseId: number): Promise<void> {
  return http.delete<void>(`/api/knowledge/bases/${knowledgeBaseId}`);
}

/* ========================= 目录树 ========================= */

/** 获取知识库的目录树 */
export function getFolderTree(
  knowledgeBaseId: number
): Promise<FolderNode[]> {
  return http.get<FolderNode[]>(
    `/api/knowledge/bases/${knowledgeBaseId}/folders/tree`
  );
}

/** 创建目录 */
export function createFolder(
  knowledgeBaseId: number,
  data: CreateFolderRequest
): Promise<number> {
  return http.post<number>(
    `/api/knowledge/bases/${knowledgeBaseId}/folders`,
    data
  );
}

/** 更新目录 */
export function updateFolder(
  knowledgeBaseId: number,
  folderId: number,
  data: UpdateFolderRequest
): Promise<void> {
  return http.put<void>(
    `/api/knowledge/bases/${knowledgeBaseId}/folders/${folderId}`,
    data
  );
}

/** 删除目录 */
export function deleteFolder(
  knowledgeBaseId: number,
  folderId: number
): Promise<void> {
  return http.delete<void>(
    `/api/knowledge/bases/${knowledgeBaseId}/folders/${folderId}`
  );
}

/* ========================= 文档 ========================= */

/**
 * 获取知识库下的文档列表（分页）。
 *
 * 注意：后端 KnowledgeDocumentController.listByBase 仅支持 current/size，
 * 不支持 folderId/title/status 筛选。这些前端筛选参数暂不发送，避免误导。
 * 若后续后端增加筛选支持，可在此扩展。
 */
export function listDocuments(
  knowledgeBaseId: number,
  params?: Pick<DocumentQuery, "current" | "size">
): Promise<PageResult<KnowledgeDocument>> {
  return http.get<PageResult<KnowledgeDocument>>(
    `/api/knowledge/bases/${knowledgeBaseId}/documents`,
    {
      params: {
        current: params?.current ?? 1,
        size: params?.size ?? 20
      }
    }
  );
}

/** 获取文档详情 */
export function getDocument(documentId: number): Promise<KnowledgeDocument> {
  return http.get<KnowledgeDocument>(`/api/knowledge/documents/${documentId}`);
}

/** Fetches authorized binary content through Gateway for preview/download. */
export function getDocumentContent(documentId: number): Promise<Blob> {
  return http.get<Blob>(`/api/knowledge/documents/${documentId}/content`, { responseType: "blob", timeout: DOCUMENT_UPLOAD_TIMEOUT });
}

export function updateDocument(documentId: number, data: UpdateDocumentRequest): Promise<void> {
  return http.put<void>(`/api/knowledge/documents/${documentId}`, data);
}

export function reingestDocument(documentId: number): Promise<void> {
  return http.post<void>(`/api/knowledge/documents/${documentId}/reingest`);
}

/** 删除文档 */
export function deleteDocument(documentId: number): Promise<void> {
  return http.delete<void>(`/api/knowledge/documents/${documentId}`);
}

/** Gateway upload timeout matches the Knowledge route's 120 second response timeout. */
export const DOCUMENT_UPLOAD_TIMEOUT = 120_000;

/**
 * Upload one document through Gateway. Do not set Content-Type: Axios/browser supplies the
 * multipart boundary for FormData, and only the server is allowed to create an object key.
 */
export function uploadDocument(
  knowledgeBaseId: number,
  request: UploadDocumentRequest,
  options?: {
    onUploadProgress?: (percent: number) => void;
    signal?: AbortSignal;
  }
): Promise<number> {
  const formData = new FormData();
  formData.append("file", request.file);
  formData.append("clientRequestId", request.clientRequestId);
  if (request.title !== undefined) formData.append("title", request.title);
  if (request.folderId !== undefined) formData.append("folderId", String(request.folderId));
  if (request.visibility !== undefined) formData.append("visibility", String(request.visibility));
  if (request.publishForChat !== undefined) formData.append("publishForChat", String(request.publishForChat));

  return http.post<number>(
    `/api/knowledge/bases/${knowledgeBaseId}/documents/upload`,
    formData,
    {
      timeout: DOCUMENT_UPLOAD_TIMEOUT,
      signal: options?.signal,
      // The dialog maps upload-specific errors to one clear user-facing message.
      skipGlobalErrorMessage: true,
      onUploadProgress: event => {
        const total = event.total;
        const raw = total && total > 0 ? (event.loaded / total) * 100 : 0;
        const percent = Number.isFinite(raw) ? Math.min(100, Math.max(0, Math.round(raw))) : 0;
        options?.onUploadProgress?.(percent);
      }
    }
  );
}

/* ========================= 成员 ========================= */

/** 获取知识库成员列表 */
export function listMembers(knowledgeBaseId: number): Promise<KnowledgeMember[]> {
  return http.get<KnowledgeMember[]>(
    `/api/knowledge/bases/${knowledgeBaseId}/members`
  );
}

/** 添加成员 */
export function addMember(
  knowledgeBaseId: number,
  data: AddMemberRequest
): Promise<void> {
  return http.post<void>(
    `/api/knowledge/bases/${knowledgeBaseId}/members`,
    data
  );
}

/** 更新成员角色 */
export function updateMemberRole(
  knowledgeBaseId: number,
  userId: number,
  data: UpdateMemberRequest
): Promise<void> {
  return http.put<void>(
    `/api/knowledge/bases/${knowledgeBaseId}/members/${userId}`,
    data
  );
}

/** 移除成员 */
export function removeMember(
  knowledgeBaseId: number,
  userId: number
): Promise<void> {
  return http.delete<void>(
    `/api/knowledge/bases/${knowledgeBaseId}/members/${userId}`
  );
}
