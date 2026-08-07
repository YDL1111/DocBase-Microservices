import { describe, it, expect, beforeEach, vi } from "vitest";
import { createRouter, createWebHashHistory } from "vue-router";
import { LAYOUT_ROUTES, LOGIN_ROUTE, ROOT_ROUTE } from "./routes";

/**
 * 验证页面刷新后动态路由恢复能力。
 *
 * 刷新场景：
 *  1. 用户已登录（token 在 sessionStorage）；
 *  2. 重新加载页面，路由实例重建；
 *  3. 守卫检测到 !permission.generated，重新拉取菜单并注册路由；
 *  4. 当前路由应能正确匹配新注册的路由。
 */
describe("knowledge route recovery after refresh", () => {
  it("RootLayout 应存在且命名正确", () => {
    const layoutRoute = LAYOUT_ROUTES[0];
    expect(layoutRoute.name).toBe("RootLayout");
    expect(layoutRoute.path).toBe("/");
  });

  it("应能通过命名父路由注册 Knowledge 子路由", async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES]
    });

    // 模拟动态路由注册（与 permission.ts 中相同）
    const knowledgeRoutes = [
      {
        path: "/knowledge",
        name: "KnowledgeList",
        component: { template: "<div>list</div>" },
        meta: { title: "知识库列表", menuId: 10 }
      },
      {
        path: "/knowledge/:id",
        name: "KnowledgeDetail",
        component: { template: "<div>detail</div>" },
        meta: { title: "知识库详情", menuId: 11 }
      }
    ];

    knowledgeRoutes.forEach(r => {
      router.addRoute("RootLayout", r);
    });

    // 验证路由注册成功
    expect(router.hasRoute("KnowledgeList")).toBe(true);
    expect(router.hasRoute("KnowledgeDetail")).toBe(true);

    // 验证路由可解析
    const listResolved = router.resolve("/knowledge");
    expect(listResolved.name).toBe("KnowledgeList");
    expect(listResolved.matched[0].name).toBe("RootLayout");

    const detailResolved = router.resolve("/knowledge/42");
    expect(detailResolved.name).toBe("KnowledgeDetail");
    expect(detailResolved.params.id).toBe("42");
  });

  it("resetRouter 应能清除动态注册的 Knowledge 路由", async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES]
    });

    // 注册动态路由
    router.addRoute("RootLayout", {
      path: "/knowledge",
      name: "KnowledgeList",
      component: { template: "<div>list</div>" },
      meta: { title: "知识库列表", menuId: 10 }
    });

    expect(router.hasRoute("KnowledgeList")).toBe(true);

    // 模拟 resetRouter（清除带 menuId 的路由）
    router.getRoutes().forEach(route => {
      if (route.name && (route.meta as any)?.menuId) {
        router.removeRoute(route.name as string);
      }
    });

    expect(router.hasRoute("KnowledgeList")).toBe(false);
    // 静态路由应保留
    expect(router.hasRoute("RootLayout")).toBe(true);
    expect(router.hasRoute("Home")).toBe(true);
  });

  it("重复注册相同名称的路由应覆盖而非重复", async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES]
    });

    // 第一次注册
    router.addRoute("RootLayout", {
      path: "/knowledge",
      name: "KnowledgeList",
      component: { template: "<div>v1</div>" },
      meta: { title: "知识库列表", menuId: 10 }
    });

    // 第二次注册（同名，模拟刷新后重新拉取菜单）
    router.addRoute("RootLayout", {
      path: "/knowledge",
      name: "KnowledgeList",
      component: { template: "<div>v2</div>" },
      meta: { title: "知识库列表", menuId: 10 }
    });

    // 应只有一个 KnowledgeList 路由
    const routes = router.getRoutes().filter(r => r.name === "KnowledgeList");
    expect(routes).toHaveLength(1);
  });
});
