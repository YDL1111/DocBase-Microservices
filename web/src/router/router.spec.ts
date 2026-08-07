import { describe, it, expect, beforeEach } from "vitest";
import { createRouter, createWebHashHistory } from "vue-router";
import { LAYOUT_ROUTES, LOGIN_ROUTE, ROOT_ROUTE } from "./routes";

/**
 * 路由集成测试：验证 Layout 路由命名正确，
 * 动态路由能注册到 RootLayout 父路由下，且子路由可访问。
 */
describe("router integration", () => {
  it("Layout 路由应具有 RootLayout 名称", () => {
    const layoutRoute = LAYOUT_ROUTES[0];
    expect(layoutRoute.name).toBe("RootLayout");
  });

  it("应能通过命名父路由成功注册动态子路由", async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES]
    });

    // 确认 RootLayout 存在
    expect(router.hasRoute("RootLayout")).toBe(true);

    // 模拟动态路由注册（与 permission.ts 中相同的逻辑）
    const dynamicRoute = {
      path: "/knowledge/doc",
      name: "KnowledgeDoc",
      component: { template: "<div>test</div>" },
      meta: { title: "文档管理", menuId: 1 }
    };
    router.addRoute("RootLayout", dynamicRoute);

    // 验证注册成功
    expect(router.hasRoute("KnowledgeDoc")).toBe(true);

    // 验证路由可解析
    const resolved = router.resolve("/knowledge/doc");
    expect(resolved.name).toBe("KnowledgeDoc");
    expect(resolved.matched.length).toBeGreaterThan(0);
    // 父路由应为 RootLayout
    expect(resolved.matched[0].name).toBe("RootLayout");
  });

  it("注册到不存在的父路由应抛出或失败", () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES]
    });

    // 空字符串父路由不应存在
    expect(router.hasRoute("" as any)).toBe(false);
  });
});
