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
    menuType: isButton ? 3 : 1,
    isButton,
    sortNum: 1,
    metaInfo: "{}",
    children
  };
}

let nextMenuId = 1;

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
  it("只展示当前 IAM 菜单树中已下发的真实业务路由", () => {
    permissionState.menus = [
      menu("Knowledge", "/knowledge", [
        menu("KnowledgeList", "/knowledge")
      ]),
      menu("AiChat", "/ai/chat"),
      menu("IngestTask", "/ingest/tasks", [], 1)
    ];

    const wrapper = mountHome();
    const links = wrapper.findAll(".entry-card");

    expect(links).toHaveLength(2);
    expect(links.map(link => link.attributes("href"))).toEqual([
      "/knowledge",
      "/ai/chat"
    ]);
    const cardText = links.map(link => link.text()).join(" ");
    expect(cardText).toContain("文档资产管理");
    expect(cardText).toContain("智能问答入口");
    expect(cardText).not.toContain("知识入库任务");
  });

  it("没有业务菜单时显示明确空状态，不渲染无权入口", () => {
    permissionState.menus = [menu("SystemManage", "/system")];

    const wrapper = mountHome();

    expect(wrapper.findAll(".entry-card")).toHaveLength(0);
    expect(wrapper.text()).toContain("当前账号暂无可用业务入口");
  });
});
