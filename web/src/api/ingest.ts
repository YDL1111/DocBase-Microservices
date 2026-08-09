/**
 * Ingest 导入任务 API 层。
 *
 * 全部走 /api/ingest/**（Gateway 路由到 ingest-service）。
 * 响应由 request.ts 拦截器剥离 ApiResponse 外壳，因此泛型 T 为业务数据本身。
 *
 * 安全约束：
 *  - 所有请求经 Gateway 转发，不绕过 Gateway 直接访问内部服务；
 *  - 不访问 RabbitMQ、MinIO、RAG 内部接口；
 *  - 不在请求 URL 中放置 Token 或敏感信息；
 *  - 不拼接 X-User-* 请求头。
 */
import { http } from "@/utils/request";
import type { PageResult, IngestTask, IngestTaskQuery } from "./types";

/* ========================= 任务列表 ========================= */

/**
 * 获取导入任务列表（分页 + 可选状态筛选）。
 * 后端仅支持 current、size、status 三个参数。
 */
export function listIngestTasks(
  params?: IngestTaskQuery
): Promise<PageResult<IngestTask>> {
  return http.get<PageResult<IngestTask>>("/api/ingest/tasks", {
    params: {
      current: params?.current ?? 1,
      size: params?.size ?? 20,
      status: params?.status ?? undefined
    }
  });
}

/* ========================= 任务详情 ========================= */

/** 获取单个任务详情 */
export function getIngestTask(taskId: number): Promise<IngestTask> {
  return http.get<IngestTask>(`/api/ingest/tasks/${taskId}`);
}

/* ========================= 重试与取消 ========================= */

/**
 * 重试任务。
 * 仅 FAILED、DEAD 状态允许（后端校验，前端仅做体验控制）。
 */
export function retryIngestTask(taskId: number): Promise<void> {
  return http.post<void>(`/api/ingest/tasks/${taskId}/retry`);
}

/**
 * 取消任务。
 * 仅 PENDING、RETRY_WAIT 状态允许（后端校验，前端仅做体验控制）。
 */
export function cancelIngestTask(taskId: number): Promise<void> {
  return http.post<void>(`/api/ingest/tasks/${taskId}/cancel`);
}
