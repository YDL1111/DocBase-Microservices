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
 * 使用真实应用路由表验证 Knowledge 路由。
 *
 * 这是 P0-1 的回归测试：验证详情路由 /knowledge/:id
 * 存在于真实应用路由表中（作为静态隐藏路由），
 * 而不是手动构造的理想路由。
 */
describe("knowledge routes - real application route table", () => {
  function createAppRouter(): Router {
    return createRouter({
      history: createWebHashHistory(),
      routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES]
    });
  }

  it("INNER_ROUTES 应包含 KnowledgeDetail 路由", () => {
    const detailRoute = INNER_ROUTES.find(r => r.name === "KnowledgeDetail");
    expect(detailRoute).toBeDefined();
    expect(detailRoute?.path).toBe("/knowledge/:id");
    expect(detailRoute?.meta?.hidden).toBe(true);
  });

  it("应用路由表应能解析 /knowledge/42", () => {
    const router = createAppRouter();
    const resolved = router.resolve("/knowledge/42");
    expect(resolved.name).toBe("KnowledgeDetail");
    expect(resolved.params.id).toBe("42");
    expect(resolved.matched.length).toBeGreaterThan(0);
  });

  it("应用路由表应能解析 /knowledge/1", () => {
    const router = createAppRouter();
    const resolved = router.resolve("/knowledge/1");
    expect(resolved.name).toBe("KnowledgeDetail");
    expect(resolved.params.id).toBe("1");
  });

  it("无效 ID 应能解析（由组件层验证）", () => {
    const router = createAppRouter();
    // 路由层不验证 ID 格式，由 detail.vue 组件层验证
    const resolved = router.resolve("/knowledge/abc");
    expect(resolved.name).toBe("KnowledgeDetail");
    // 组件层会将 Number("abc") → NaN 视为无效并重定向 404
  });

  it("路由表不应包含重复的 KnowledgeDetail", () => {
    const router = createAppRouter();
    const routes = router.getRoutes().filter(
      r => r.name === "KnowledgeDetail"
    );
    expect(routes).toHaveLength(1);
  });

  it("RootLayout 应作为 KnowledgeDetail 的父路由", () => {
    const router = createAppRouter();
    const resolved = router.resolve("/knowledge/42");
    expect(resolved.matched[0].name).toBe("RootLayout");
  });
});
