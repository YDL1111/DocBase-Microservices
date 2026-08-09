import { describe, it, expect, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { usePermissionStore } from "./permission";
import type { MenuNode } from "@/api/types";

describe("permission store - component registry", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  const makeMenuNode = (overrides: Partial<MenuNode>): MenuNode => ({
    menuId: 1,
    parentId: 0,
    menuName: "测试",
    routerName: "",
    path: "/test",
    permission: "",
    menuType: 1,
    isButton: 0,
    sortNum: 0,
    metaInfo: "",
    ...overrides
  });

  it("buildRoutes 应跳过按钮类型（isButton===1）", () => {
    const store = usePermissionStore();
    const nodes: MenuNode[] = [
      makeMenuNode({ menuId: 1, menuName: "菜单", isButton: 0 }),
      makeMenuNode({ menuId: 2, menuName: "按钮权限", isButton: 1, permission: "knowledge:base:create" })
    ];
    const routes = store.buildRoutes(nodes);
    expect(routes).toHaveLength(1);
    expect(routes[0].meta?.title).toBe("菜单");
  });

  it("nodeToRoute 应为 KnowledgeList 生成正确路由", () => {
    const store = usePermissionStore();
    const node = makeMenuNode({
      menuId: 10,
      menuName: "知识库列表",
      routerName: "KnowledgeList",
      path: "/knowledge",
      permission: "knowledge:base:list"
    });
    const route = store.nodeToRoute(node);
    expect(route.name).toBe("KnowledgeList");
    expect(route.path).toBe("/knowledge");
    expect(route.meta?.title).toBe("知识库列表");
    expect(route.meta?.permission).toBe("knowledge:base:list");
    // component 应是函数（懒加载）
    expect(typeof route.component).toBe("function");
  });

  it("nodeToRoute 应为 KnowledgeDetail 生成正确路由", () => {
    const store = usePermissionStore();
    const node = makeMenuNode({
      menuId: 11,
      menuName: "知识库详情",
      routerName: "KnowledgeDetail",
      path: "/knowledge/:id"
    });
    const route = store.nodeToRoute(node);
    expect(route.name).toBe("KnowledgeDetail");
    expect(route.path).toBe("/knowledge/:id");
  });

  it("nodeToRoute maps AiChat to the real Chat page and excludes button permissions", async () => {
    const store = usePermissionStore();
    const route = store.nodeToRoute(makeMenuNode({
      menuId: 40,
      routerName: "AiChat",
      path: "/ai/chat",
      permission: "ai:chat:list"
    }));
    expect(route.path).toBe("/ai/chat");
    expect((await (route.component as () => Promise<any>)()).default.name).toBe("AiChat");
    expect(store.buildRoutes([makeMenuNode({ menuId: 41, routerName: "", isButton: 1, permission: "ai:chat:query" })])).toEqual([]);
  });

  it("未注册的 routerName 应回退到占位组件", () => {
    const store = usePermissionStore();
    const node = makeMenuNode({
      menuId: 99,
      routerName: "UnknownPage",
      path: "/unknown"
    });
    const route = store.nodeToRoute(node);
    expect(route.name).toBe("UnknownPage");
    // component 仍是函数（占位页也是懒加载）
    expect(typeof route.component).toBe("function");
  });

  it("无 routerName 时应使用 menu-{id} 作为路由名", () => {
    const store = usePermissionStore();
    const node = makeMenuNode({
      menuId: 55,
      routerName: "",
      path: "/test"
    });
    const route = store.nodeToRoute(node);
    expect(route.name).toBe("menu-55");
  });
});
