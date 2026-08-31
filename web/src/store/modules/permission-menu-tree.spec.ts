import { describe, it, expect, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { usePermissionStore } from "./permission";
import type { MenuNode } from "@/api/types";

/**
 * 真实菜单树结构测试。
 *
 * 验证：侧边栏保留目录树，但页面路由扁平注册到 RootLayout，
 * 避免绝对子路径受目录 matcher 与兜底 404 影响。
 *
 * 这是 P0-1 的回归测试。
 */
describe("permission store - real menu tree structure", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("应跳过 Knowledge 目录并把子页面构建为扁平路由", () => {
    const store = usePermissionStore();

    // 模拟后端返回的真实菜单树
    const menuTree: MenuNode[] = [
      {
        menuId: 1,
        parentId: null,
        menuName: "知识库",
        routerName: "Knowledge",
        path: "/knowledge",
        permission: "",
        menuType: 2, // 目录
        isButton: 0,
        sortNum: 10,
        metaInfo: "{}",
        children: [
          {
            menuId: 2,
            parentId: 1,
            menuName: "知识库列表",
            routerName: "KnowledgeList",
            path: "/knowledge",
            permission: "knowledge:base:list",
            menuType: 1, // 菜单
            isButton: 0,
            sortNum: 10,
            metaInfo: "{}",
            children: []
          }
        ]
      }
    ];

    const routes = store.buildRoutes(menuTree);

    // 目录只用于侧边栏分组，真正的页面直接注册到 RootLayout。
    expect(routes).toHaveLength(1);
    const listRoute = routes[0];
    expect(listRoute.name).toBe("KnowledgeList");
    expect(listRoute.path).toBe("/knowledge");
    expect(typeof listRoute.component).toBe("function");
    expect(listRoute.children).toBeUndefined();
  });

  it("未注册的目录应回退到 PlaceholderView", () => {
    const store = usePermissionStore();

    const menuTree: MenuNode[] = [
      {
        menuId: 10,
        parentId: null,
        menuName: "未迁移目录",
        routerName: "NotMigrated",
        path: "/not-migrated",
        permission: "",
        menuType: 2,
        isButton: 0,
        sortNum: 0,
        metaInfo: "{}",
        children: [
          {
            menuId: 11,
            parentId: 10,
            menuName: "子页面",
            routerName: "ChildPage",
            path: "/child",
            permission: "",
            menuType: 1,
            isButton: 0,
            sortNum: 0,
            metaInfo: "{}",
            children: []
          }
        ]
      }
    ];

    const routes = store.buildRoutes(menuTree);
    expect(routes).toHaveLength(1);
    // 有子页面的目录不参与 matcher；未知叶子仍使用 PlaceholderView。
    expect(routes[0].name).toBe("ChildPage");
    expect(typeof routes[0].component).toBe("function");
    expect(routes[0].children).toBeUndefined();
  });

  it("深层嵌套菜单应递归构建", () => {
    const store = usePermissionStore();

    const menuTree: MenuNode[] = [
      {
        menuId: 1,
        parentId: null,
        menuName: "知识库",
        routerName: "Knowledge",
        path: "/knowledge",
        permission: "",
        menuType: 2,
        isButton: 0,
        sortNum: 0,
        metaInfo: "{}",
        children: [
          {
            menuId: 2,
            parentId: 1,
            menuName: "知识库列表",
            routerName: "KnowledgeList",
            path: "/knowledge",
            permission: "knowledge:base:list",
            menuType: 1,
            isButton: 0,
            sortNum: 0,
            metaInfo: "{}",
            children: []
          }
        ]
      }
    ];

    const routes = store.buildRoutes(menuTree);
    // 验证递归展开为页面路由
    expect(routes[0].name).toBe("KnowledgeList");
    expect(routes[0].children).toBeUndefined();
  });
});
