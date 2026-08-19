import { describe, expect, it } from "vitest";
import type { MenuNode } from "@/api/types";
import { buildSidebarNavigation, menuContainsPath } from "./navigation";

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
    permission: isButton ? "action:test" : "",
    menuType: isButton ? 3 : children.length ? 2 : 1,
    isButton,
    sortNum: nextMenuId,
    metaInfo: "{}",
    children
  };
}

describe("侧栏导航归组", () => {
  it("展示首页、全部真实业务页面和系统管理页面", () => {
    const tree = [
      menu("Knowledge", "/knowledge", [
        menu("KnowledgeList", "/knowledge", [
          menu("KnowledgeCreateButton", "", [], 1)
        ])
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

    const navigation = buildSidebarNavigation(tree);

    expect(navigation.map(item => item.routerName)).toEqual([
      "Home",
      "SidebarKnowledgeGroup",
      "AiChat",
      "SystemManage"
    ]);
    expect(navigation[1].children?.map(item => item.routerName)).toEqual([
      "KnowledgeList",
      "IngestTask"
    ]);
    expect(navigation[2].children).toEqual([]);
    expect(navigation[3].children?.map(item => item.routerName)).toEqual([
      "SystemUser",
      "SystemRole",
      "SystemMenu"
    ]);
  });

  it("不会为未授权页面补造入口，并保留未来新增的未知根菜单", () => {
    const navigation = buildSidebarNavigation([
      menu("Knowledge", "/knowledge", [
        menu("KnowledgeList", "/knowledge")
      ]),
      menu("FutureReport", "/reports")
    ]);

    expect(
      navigation
        .flatMap(item => (item.children?.length ? item.children : [item]))
        .map(item => item.routerName)
    ).toEqual(["Home", "KnowledgeList", "FutureReport"]);
    expect(JSON.stringify(navigation)).not.toContain("SystemUser");
    expect(JSON.stringify(navigation)).not.toContain("IngestTask");
  });

  it("能识别嵌套页面所属的展开目录", () => {
    const [home, knowledge] = buildSidebarNavigation([
      menu("Knowledge", "/knowledge", [
        menu("KnowledgeList", "/knowledge")
      ])
    ]);

    expect(menuContainsPath(home, "/knowledge")).toBe(false);
    expect(menuContainsPath(knowledge, "/knowledge")).toBe(true);
  });
});
