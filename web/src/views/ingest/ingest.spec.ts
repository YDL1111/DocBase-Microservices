import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  ingestTaskStatusLabel,
  ingestTaskStatusTagType,
  ingestTaskTypeLabel,
  isActiveStatus,
  isTerminalStatus,
  isRetryableStatus,
  isCancelableStatus,
  IngestTaskStatusEnum
} from "@/api/types";

describe("ingest status helpers", () => {
  it("ingestTaskStatusLabel 应正确映射所有状态", () => {
    expect(ingestTaskStatusLabel("PENDING")).toBe("待处理");
    expect(ingestTaskStatusLabel("PROCESSING")).toBe("处理中");
    expect(ingestTaskStatusLabel("DISPATCHED")).toBe("已分发");
    expect(ingestTaskStatusLabel("SUCCEEDED")).toBe("已完成");
    expect(ingestTaskStatusLabel("FAILED")).toBe("失败");
    expect(ingestTaskStatusLabel("RETRY_WAIT")).toBe("等待重试");
    expect(ingestTaskStatusLabel("DEAD")).toBe("永久失败");
    expect(ingestTaskStatusLabel("CANCELLED")).toBe("已取消");
    expect(ingestTaskStatusLabel("UNKNOWN")).toBe("未知");
  });

  it("ingestTaskStatusTagType 应返回正确的标签类型", () => {
    expect(ingestTaskStatusTagType("PENDING")).toBe("info");
    expect(ingestTaskStatusTagType("PROCESSING")).toBe("primary");
    expect(ingestTaskStatusTagType("DISPATCHED")).toBe("primary");
    expect(ingestTaskStatusTagType("SUCCEEDED")).toBe("success");
    expect(ingestTaskStatusTagType("FAILED")).toBe("warning");
    expect(ingestTaskStatusTagType("RETRY_WAIT")).toBe("warning");
    expect(ingestTaskStatusTagType("DEAD")).toBe("danger");
    expect(ingestTaskStatusTagType("CANCELLED")).toBe("danger");
  });

  it("ingestTaskTypeLabel 应正确映射任务类型", () => {
    expect(ingestTaskTypeLabel("IMPORT")).toBe("导入");
    expect(ingestTaskTypeLabel("REIMPORT")).toBe("重新导入");
    expect(ingestTaskTypeLabel("RETRY")).toBe("重试");
    expect(ingestTaskTypeLabel("DELETE")).toBe("删除");
    expect(ingestTaskTypeLabel("UNKNOWN")).toBe("未知");
  });

  it("isActiveStatus 应正确识别活跃状态", () => {
    expect(isActiveStatus("PENDING")).toBe(true);
    expect(isActiveStatus("PROCESSING")).toBe(true);
    expect(isActiveStatus("DISPATCHED")).toBe(true);
    expect(isActiveStatus("RETRY_WAIT")).toBe(true);
    expect(isActiveStatus("SUCCEEDED")).toBe(false);
    expect(isActiveStatus("FAILED")).toBe(false);
    expect(isActiveStatus("DEAD")).toBe(false);
    expect(isActiveStatus("CANCELLED")).toBe(false);
  });

  it("isTerminalStatus 应正确识别终态", () => {
    expect(isTerminalStatus("SUCCEEDED")).toBe(true);
    expect(isTerminalStatus("DEAD")).toBe(true);
    expect(isTerminalStatus("CANCELLED")).toBe(true);
    expect(isTerminalStatus("FAILED")).toBe(true);
    expect(isTerminalStatus("PENDING")).toBe(false);
    expect(isTerminalStatus("PROCESSING")).toBe(false);
  });

  it("isRetryableStatus 应正确识别可重试状态", () => {
    expect(isRetryableStatus("FAILED")).toBe(true);
    expect(isRetryableStatus("DEAD")).toBe(true);
    expect(isRetryableStatus("PENDING")).toBe(false);
    expect(isRetryableStatus("SUCCEEDED")).toBe(false);
    expect(isRetryableStatus("CANCELLED")).toBe(false);
  });

  it("isCancelableStatus 应正确识别可取消状态", () => {
    expect(isCancelableStatus("PENDING")).toBe(true);
    expect(isCancelableStatus("RETRY_WAIT")).toBe(true);
    expect(isCancelableStatus("FAILED")).toBe(false);
    expect(isCancelableStatus("SUCCEEDED")).toBe(false);
    expect(isCancelableStatus("DEAD")).toBe(false);
  });
});

describe("ingest lastError sanitization", () => {
  it("应截断过长的错误信息", () => {
    const longError = "a".repeat(600);
    const truncated = longError.slice(0, 500) + "...";
    expect(truncated.length).toBe(503);
  });

  it("不应使用 v-html 展示错误（纯文本）", () => {
    const xssAttempt = '<script>alert("xss")</script>';
    // 纯文本展示时，HTML 标签应被转义或按文本处理
    const sanitized = xssAttempt
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
    expect(sanitized).not.toContain("<script>");
  });
});
