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
      menu("SystemOrganization", "/system/organizations"),
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
  it("管理员菜单树展示全部授权功能入口及检索预览", () => {
    permissionState.menus = fullMenuTree();

    const wrapper = mountHome();
    const links = wrapper.findAll(".entry-card");

    expect(links).toHaveLength(8);
    expect(links.map(link => link.attributes("href"))).toEqual([
      "/search",
      "/knowledge",
      "/ingest/tasks",
      "/ai/chat",
      "/system/organizations",
      "/system/users",
      "/system/roles",
      "/system/menus"
    ]);
    expect(wrapper.text()).toContain("文档与知识");
    expect(wrapper.text()).toContain("权限管理");
    expect(wrapper.text()).toContain("8可用模块");
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
      "/search",
      "/knowledge",
      "/ai/chat"
    ]);
    expect(wrapper.text()).not.toContain("权限管理");
    expect(wrapper.text()).not.toContain("用户管理");
  });

  it("系统入口使用 IAM 真实路径而不是固定复数路径", () => {
    permissionState.menus = [menu("SystemManage", "/system", [
      menu("SystemOrganization", "/system/organization"), menu("SystemUser", "/system/user"), menu("SystemRole", "/system/role"), menu("SystemMenu", "/system/menu")
    ])];
    const wrapper = mountHome();
    expect(wrapper.findAll(".entry-card").map(link => link.attributes("href")))
      .toEqual(["/search", "/system/organization", "/system/user", "/system/role", "/system/menu"]);
  });

  it("没有业务权限时仅显示本地检索预览", () => {
    permissionState.menus = [menu("OnlyAction", "", [], 1)];

    const wrapper = mountHome();

    expect(wrapper.findAll(".entry-card")).toHaveLength(1);
    expect(wrapper.find(".entry-card").attributes("href")).toBe("/search");
    expect(wrapper.text()).not.toContain("组织管理");
  });
});
