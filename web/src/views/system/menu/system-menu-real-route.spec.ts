import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount } from "@vue/test-utils";
import { createRouter, createWebHashHistory } from "vue-router";
import { setActivePinia, createPinia } from "pinia";
import { nextTick } from "vue";
import ElementPlus from "element-plus";

/**
 * 动态菜单真实注册并渲染验证（P0）。
 *
 * 菜单树结构与后端 V12__system_menu_seed.sql 一致：
 *   系统管理（仅用于侧边栏下拉分组，is_system=1）
 *     ├── 用户管理（SystemUser）
 *     ├── 角色管理（SystemRole）
 *     └── 菜单管理（SystemMenu → 真实页面组件，is_system=1）
 *         └── 按钮：新建菜单 / 编辑菜单 / 删除菜单（menuType=3，不生成路由）
 *
 * 验证 componentRegistry 中 SystemMenu 已注册为真实组件，
 * 动态路由渲染的是 views/system/menu/index.vue，而不是 PlaceholderView。
 */

let listMenuTreeMock: any = vi.fn();
vi.mock("@/api/system-menu", () => ({
  listMenuTree: (...args: any[]) => listMenuTreeMock(...args)
}));
vi.mock("@/utils/message", () => ({
  message: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(undefined)
  }
}));
vi.mock("element-plus", async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) }
  };
});

import { usePermissionStore } from "@/store/modules/permission";
import type { MenuNode } from "@/api/types";

async function flushPromises(times = 8) {
  for (let i = 0; i < times; i++) {
    await nextTick();
    await Promise.resolve();
  }
}

/** 与 V12__system_menu_seed.sql 一致的 SystemManage 目录 + SystemUser/SystemRole/SystemMenu 结构 */
function systemMenuTree(): MenuNode[] {
  return [
    {
      menuId: 1,
      parentId: null,
      menuName: "系统管理",
      routerName: "SystemManage",
      path: "/system",
      permission: "",
      menuType: 2,
      isButton: 0,
      sortNum: 40,
      metaInfo: "{}",
      status: 1,
      isSystem: 1,
      children: [
        {
          menuId: 2,
          parentId: 1,
          menuName: "用户管理",
          routerName: "SystemUser",
          path: "/system/user",
          permission: "system:user:list",
          menuType: 1,
          isButton: 0,
          sortNum: 40,
          metaInfo: "{}",
          status: 1,
          isSystem: 1,
          children: []
        },
        {
          menuId: 3,
          parentId: 1,
          menuName: "角色管理",
          routerName: "SystemRole",
          path: "/system/role",
          permission: "system:role:list",
          menuType: 1,
          isButton: 0,
          sortNum: 46,
          metaInfo: "{}",
          status: 1,
          isSystem: 1,
          children: []
        },
        {
          menuId: 4,
          parentId: 1,
          menuName: "菜单管理",
          routerName: "SystemMenu",
          path: "/system/menu",
          permission: "system:menu:list",
          menuType: 1,
          isButton: 0,
          sortNum: 52,
          metaInfo: "{}",
          status: 1,
          isSystem: 1,
          children: [
            {
              menuId: 5,
              parentId: 4,
              menuName: "新建菜单",
              routerName: "",
              path: "",
              permission: "system:menu:create",
              menuType: 3,
              isButton: 1,
              sortNum: 53,
              metaInfo: "{}",
              status: 1,
              isSystem: 1,
              children: []
            },
            {
              menuId: 6,
              parentId: 4,
              menuName: "编辑菜单",
              routerName: "",
              path: "",
              permission: "system:menu:update",
              menuType: 3,
              isButton: 1,
              sortNum: 54,
              metaInfo: "{}",
              status: 1,
              isSystem: 1,
              children: []
            },
            {
              menuId: 7,
              parentId: 4,
              menuName: "删除菜单",
              routerName: "",
              path: "",
              permission: "system:menu:delete",
              menuType: 3,
              isButton: 1,
              sortNum: 55,
              metaInfo: "{}",
              status: 1,
              isSystem: 1,
              children: []
            }
          ]
        }
      ]
    }
  ];
}

describe("SystemMenu 动态路由真实渲染", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    listMenuTreeMock.mockReset().mockResolvedValue([
      { menuId: 1, parentId: 0, menuName: "系统管理", routerName: "SystemManage", path: "/system", permission: "", menuType: 2, isButton: 0, sortNum: 40, status: 1, isSystem: 1, children: [] }
    ]);
  });

  it("componentRegistry 注册 SystemMenu：buildRoutes 生成真实组件而非 PlaceholderView", async () => {
    const store = usePermissionStore();
    const routes = store.buildRoutes(systemMenuTree());

    expect(routes).toHaveLength(3);
    const routeNames = routes.map(route => route.name);
    expect(routeNames).toEqual(["SystemUser", "SystemRole", "SystemMenu"]);
    // 按钮节点（menuType=3）不生成路由
    expect(routeNames).not.toContain("新建菜单");
    expect(routeNames).not.toContain("编辑菜单");
    expect(routeNames).not.toContain("删除菜单");

    const menuRoute = routes.find(route => route.name === "SystemMenu")!;
    expect(typeof menuRoute.component).toBe("function");

    // 动态加载的组件模块必须是真实页面（views/system/menu/index.vue），
    // 而非 placeholder。加载后模块 default 的 name 应为 SystemMenu。
    const mod = await (menuRoute.component as () => Promise<any>)();
    expect(mod.default.name).toBe("SystemMenu");
  });

  it("真实动态路由跳转 /system/menu 应渲染菜单管理页面（不回退 PlaceholderView）", async () => {
    const store = usePermissionStore();
    const routes = store.buildRoutes(systemMenuTree());

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: "/",
          name: "RootLayout",
          component: { template: "<router-view />" },
          children: []
        }
      ]
    });
    routes.forEach(r => router.addRoute("RootLayout", r));

    const wrapper = mount(
      { template: "<router-view />" },
      { global: { plugins: [router, ElementPlus] } }
    );

    await router.push("/system/menu");
    await flushPromises();

    // 渲染真实页面：菜单管理标题 + 新建按钮存在
    expect(wrapper.find(".menu-manage").exists()).toBe(true);
    expect(wrapper.text()).toContain("菜单管理");
    expect(wrapper.findAll("button").some(b => b.text().includes("新建菜单"))).toBe(true);
    // 绝不回退占位页
    expect(wrapper.text()).not.toContain("功能建设中");
  });

  it("SystemManage 仅作为导航目录，页面路由直接挂载 RootLayout", async () => {
    const store = usePermissionStore();
    const routes = store.buildRoutes(systemMenuTree());
    expect(routes.some(route => route.name === "SystemManage")).toBe(false);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: "/",
          name: "RootLayout",
          component: { template: "<router-view />" },
          children: []
        }
      ]
    });
    routes.forEach(r => router.addRoute("RootLayout", r));

    const wrapper = mount(
      { template: "<router-view />" },
      { global: { plugins: [router, ElementPlus] } }
    );

    await router.push("/system/menu");
    await flushPromises();
    expect(router.currentRoute.value.name).toBe("SystemMenu");
    expect(router.currentRoute.value.matched.map(record => record.name)).toEqual([
      "RootLayout",
      "SystemMenu"
    ]);
    expect(wrapper.text()).toContain("菜单管理");
  });
});
