import { defineComponent } from "vue";
import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import type { SidebarMenuNode } from "./navigation";
import SidebarItem from "./sidebarItem.vue";

let nextMenuId = 1;

function menu(
  routerName: string,
  path: string,
  children: SidebarMenuNode[] = [],
  isButton = 0
): SidebarMenuNode {
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

const ElMenuItemStub = defineComponent({
  name: "ElMenuItem",
  props: { index: { type: String, default: "" } },
  template: '<div class="menu-item-stub" :data-index="index"><slot /></div>'
});

const ElSubMenuStub = defineComponent({
  name: "ElSubMenu",
  props: { index: { type: String, default: "" } },
  template:
    '<section class="sub-menu-stub" :data-index="index"><slot name="title" /><slot /></section>'
});

function mountItem(item: SidebarMenuNode) {
  return mount(SidebarItem, {
    props: { item, basePath: item.path },
    global: {
      stubs: {
        ElMenuItem: ElMenuItemStub,
        ElSubMenu: ElSubMenuStub,
        ElIcon: { template: "<i><slot /></i>" }
      }
    }
  });
}

describe("侧栏节点渲染", () => {
  it("只有一个真实子页面的目录仍保留下拉层级", () => {
    const wrapper = mountItem(
      menu("Knowledge", "/knowledge", [
        menu("KnowledgeList", "/knowledge")
      ])
    );

    expect(wrapper.findAll(".sub-menu-stub")).toHaveLength(1);
    expect(wrapper.findAll(".menu-item-stub")).toHaveLength(1);
    expect(wrapper.find(".menu-item-stub").attributes("data-index")).toBe(
      "/knowledge"
    );
  });

  it("只有按钮权限子节点的页面渲染为叶子，不产生空下拉", () => {
    const wrapper = mountItem(
      menu("AiChat", "/ai/chat", [menu("AiChatQuery", "", [], 1)])
    );

    expect(wrapper.find(".sub-menu-stub").exists()).toBe(false);
    expect(wrapper.find(".menu-item-stub").attributes("data-index")).toBe(
      "/ai/chat"
    );
  });
});
