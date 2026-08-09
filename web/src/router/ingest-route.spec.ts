import { describe, it, expect } from "vitest";
import {
  createRouter,
  createWebHashHistory,
  type Router
} from "vue-router";
import {
  ROOT_ROUTE,
  LOGIN_ROUTE,
  LAYOUT_ROUTES,
  INNER_ROUTES
} from "./routes";

/**
 * 使用真实应用路由表验证 Ingest 路由。
 */
describe("ingest routes - real application route table", () => {
  function createAppRouter(): Router {
    return createRouter({
      history: createWebHashHistory(),
      routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES]
    });
  }

  it("INNER_ROUTES 应包含 IngestTaskDetail 路由", () => {
    const detailRoute = INNER_ROUTES.find(r => r.name === "IngestTaskDetail");
    expect(detailRoute).toBeDefined();
    expect(detailRoute?.path).toBe("/ingest/tasks/:taskId");
    expect(detailRoute?.meta?.hidden).toBe(true);
  });

  it("应用路由表应能解析 /ingest/tasks/42", () => {
    const router = createAppRouter();
    const resolved = router.resolve("/ingest/tasks/42");
    expect(resolved.name).toBe("IngestTaskDetail");
    expect(resolved.params.taskId).toBe("42");
  });

  it("应用路由表应能解析 /ingest/tasks/1", () => {
    const router = createAppRouter();
    const resolved = router.resolve("/ingest/tasks/1");
    expect(resolved.name).toBe("IngestTaskDetail");
    expect(resolved.params.taskId).toBe("1");
  });

  it("路由表不应包含重复的 IngestTaskDetail", () => {
    const router = createAppRouter();
    const routes = router.getRoutes().filter(
      r => r.name === "IngestTaskDetail"
    );
    expect(routes).toHaveLength(1);
  });

  it("RootLayout 应作为 IngestTaskDetail 的父路由", () => {
    const router = createAppRouter();
    const resolved = router.resolve("/ingest/tasks/42");
    expect(resolved.matched[0].name).toBe("RootLayout");
  });
});

/**
 * 路由参数验证逻辑测试（与 detail.vue 中的实现一致）。
 */
function validateTaskId(raw: unknown): number | null {
  const num = Number(raw);
  if (!Number.isInteger(num) || num <= 0 || !Number.isSafeInteger(num)) {
    return null;
  }
  return num;
}

describe("ingest task id validation", () => {
  it("应接受有效的正整数 ID", () => {
    expect(validateTaskId("42")).toBe(42);
    expect(validateTaskId("1")).toBe(1);
    expect(validateTaskId("999999")).toBe(999999);
  });

  it("应拒绝 NaN", () => {
    expect(validateTaskId("abc")).toBeNull();
    expect(validateTaskId("")).toBeNull();
    expect(validateTaskId(":taskId")).toBeNull();
  });

  it("应拒绝负数和非安全整数", () => {
    expect(validateTaskId("-1")).toBeNull();
    expect(validateTaskId("0")).toBeNull();
    expect(validateTaskId("9007199254740992")).toBeNull();
  });
});
