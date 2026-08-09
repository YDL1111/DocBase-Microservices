import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  listIngestTasks,
  getIngestTask,
  retryIngestTask,
  cancelIngestTask
} from "./ingest";

// 模拟 request 层
vi.mock("@/utils/request", () => ({
  http: {
    get: vi.fn(),
    post: vi.fn()
  }
}));

import { http } from "@/utils/request";

describe("ingest api", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("listIngestTasks 应 GET /api/ingest/tasks 并携带分页和状态参数", async () => {
    (http.get as any).mockResolvedValue({ records: [], total: 0 });
    await listIngestTasks({ current: 1, size: 20, status: "PENDING" });
    expect(http.get).toHaveBeenCalledWith("/api/ingest/tasks", {
      params: { current: 1, size: 20, status: "PENDING" }
    });
  });

  it("listInGET 应只发送 current/size（不发送不存在的参数）", async () => {
    (http.get as any).mockResolvedValue({ records: [], total: 0 });
    await listIngestTasks({ current: 2, size: 10 });
    const call = (http.get as any).mock.calls[0];
    // 确保不发送 folderId/title 等后端不支持的参数
    expect(call[1].params).toHaveProperty("current", 2);
    expect(call[1].params).toHaveProperty("size", 10);
    expect(call[1].params).not.toHaveProperty("folderId");
    expect(call[1].params).not.toHaveProperty("title");
  });

  it("getIngestTask 应 GET /api/ingest/tasks/{taskId}", async () => {
    (http.get as any).mockResolvedValue({});
    await getIngestTask(42);
    expect(http.get).toHaveBeenCalledWith("/api/ingest/tasks/42");
  });

  it("retryIngestTask 应 POST /api/ingest/tasks/{taskId}/retry", async () => {
    (http.post as any).mockResolvedValue(undefined);
    await retryIngestTask(42);
    expect(http.post).toHaveBeenCalledWith(
      "/api/ingest/tasks/42/retry"
    );
  });

  it("cancelIngestTask 应 POST /api/ingest/tasks/{taskId}/cancel", async () => {
    (http.post as any).mockResolvedValue(undefined);
    await cancelIngestTask(42);
    expect(http.post).toHaveBeenCalledWith(
      "/api/ingest/tasks/42/cancel"
    );
  });
});
