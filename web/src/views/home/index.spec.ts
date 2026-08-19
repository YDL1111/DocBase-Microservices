import { afterEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import type { MenuNode } from "@/api/types";
import HomeView from "./index.vue";

const { permissionState, userState } = vi.hoisted(() => ({
  permissionState: { menus: [] as MenuNode[] },
  userState: { displayName: "测试用户" }
}));

vi.mock("@/store/modules/permission", () => ({
  usePermissionStoreHook: () => permissionState
}));

vi.mock("@/store/modules/user", () => ({
  useUserStoreHook: () => userState
}));

let nextMenuId = 1;

function menu(
  routerName: string,
  path: string,
  children: MenuNode[] = [],
  isButton = 0
): MenuNode {
  return {
    menuId: nextMenuId++,
    parentId: 0,
    menuName: routerName,
    routerName,
    path,
    permission: "",
    menuType: isButton ? 3 : children.length ? 2 : 1,
    isButton,
    sortNum: 1,
    metaInfo: "{}",
    children
  };
}

function fullMenuTree(): MenuNode[] {
  return [
    menu("Knowledge", "/knowledge", [
      menu("KnowledgeList", "/knowledge")
    ]),
    menu("IngestTaskDir", "/ingest", [
      menu("IngestTask", "/ingest/tasks")
    ]),
    menu("AiChat", "/ai/chat", [menu("AiChatQuery", "", [], 1)]),
    menu("SystemManage", "/system", [
      menu("SystemUser", "/system/users"),
      menu("SystemRole", "/system/roles"),
      menu("SystemMenu", "/system/menus")
    ])
  ];
}

function mountHome() {
  return mount(HomeView, {
    global: {
      stubs: {
        RouterLink: {
          props: ["to"],
          template: '<a class="router-link-stub" :href="to"><slot /></a>'
        },
        ElIcon: { template: "<i><slot /></i>" },
        ElEmpty: {
          props: ["description"],
          template: '<div class="empty-stub">{{ description }}</div>'
        }
      }
    }
  });
}

afterEach(() => {
  permissionState.menus = [];
});

describe("首页工作台权限入口", () => {
  it("管理员菜单树展示全部六个真实功能入口", () => {
    permissionState.menus = fullMenuTree();

    const wrapper = mountHome();
    const links = wrapper.findAll(".entry-card");

    expect(links).toHaveLength(6);
    expect(links.map(link => link.attributes("href"))).toEqual([
      "/knowledge",
      "/ingest/tasks",
      "/ai/chat",
      "/system/users",
      "/system/roles",
      "/system/menus"
    ]);
    expect(wrapper.text()).toContain("业务工作区");
    expect(wrapper.text()).toContain("系统治理");
    expect(wrapper.text()).toContain("6可用模块");
  });

  it("只展示 IAM 已下发页面，按钮权限不会变成入口", () => {
    permissionState.menus = [
      menu("Knowledge", "/knowledge", [
        menu("KnowledgeList", "/knowledge", [
          menu("SystemUser", "", [], 1)
        ])
      ]),
      menu("AiChat", "/ai/chat")
    ];

    const wrapper = mountHome();
    const links = wrapper.findAll(".entry-card");

    expect(links.map(link => link.attributes("href"))).toEqual([
      "/knowledge",
      "/ai/chat"
    ]);
    expect(wrapper.text()).not.toContain("系统治理");
    expect(wrapper.text()).not.toContain("用户管理");
  });

  it("没有页面菜单时显示明确空状态", () => {
    permissionState.menus = [menu("OnlyAction", "", [], 1)];

    const wrapper = mountHome();

    expect(wrapper.findAll(".entry-card")).toHaveLength(0);
    expect(wrapper.text()).toContain("当前账号暂无可用业务入口");
  });
});
