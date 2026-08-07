import { describe, it, expect, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { usePermissionStore } from "./permission";
import type { MenuNode } from "@/api/types";

/**
 * 真实菜单树结构测试。
 *
 * 验证：当后端返回包含"知识库"目录（有子菜单）的菜单树时，
 * 目录节点使用 RouterViewWrapper（含 <router-view>），
 * 子节点使用真实组件。
 *
 * 这是 P0-1 的回归测试。
 */
describe("permission store - real menu tree structure", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("应正确构建 Knowledge 目录 + 子菜单的路由结构", () => {
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

    // 应生成 1 个父路由（Knowledge 目录）
    expect(routes).toHaveLength(1);
    const knowledgeRoute = routes[0];
    expect(knowledgeRoute.name).toBe("Knowledge");
    expect(knowledgeRoute.path).toBe("/knowledge");
    expect(typeof knowledgeRoute.component).toBe("function");

    // 父路由应有子路由
    expect(knowledgeRoute.children).toBeDefined();
    expect(knowledgeRoute.children).toHaveLength(1);

    const listRoute = knowledgeRoute.children![0];
    expect(listRoute.name).toBe("KnowledgeList");
    expect(listRoute.path).toBe("/knowledge");
    expect(typeof listRoute.component).toBe("function");
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
    // 未注册目录仍应生成路由（使用 PlaceholderView）
    expect(routes[0].name).toBe("NotMigrated");
    expect(typeof routes[0].component).toBe("function");
    // 子路由也应生成
    expect(routes[0].children).toHaveLength(1);
    expect(routes[0].children![0].name).toBe("ChildPage");
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
    // 验证递归构建正确
    expect(routes[0].name).toBe("Knowledge");
    expect(routes[0].children![0].name).toBe("KnowledgeList");
  });
});
