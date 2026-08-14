import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  listMenus,
  listMenuTree,
  getMenu,
  getMenuOwners,
  replaceMenuOwners,
  createMenu,
  updateMenu,
  changeMenuStatus,
  deleteMenu
} from "./system-menu";

// 模拟 request 层
vi.mock("@/utils/request", () => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}));

import { http } from "@/utils/request";

describe("system-menu api", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /* ================= 路径与 method 精确匹配 ================= */

  it("listMenus 应 GET /api/system/menus", async () => {
    (http.get as any).mockResolvedValue([]);
    await listMenus();
    expect(http.get).toHaveBeenCalledWith("/api/system/menus");
  });

  it("listMenuTree 应 GET /api/system/menus/tree", async () => {
    (http.get as any).mockResolvedValue([]);
    await listMenuTree();
    expect(http.get).toHaveBeenCalledWith("/api/system/menus/tree");
  });

  it("getMenu 应 GET /api/system/menus/{menuId}", async () => {
    (http.get as any).mockResolvedValue({ menuId: 7 });
    await getMenu(7);
    expect(http.get).toHaveBeenCalledWith("/api/system/menus/7");
  });

  it("getMenuOwners 应 GET /api/system/menus/{menuId}/owners", async () => {
    (http.get as any).mockResolvedValue([1, 2]);
    await getMenuOwners(7);
    expect(http.get).toHaveBeenCalledWith("/api/system/menus/7/owners");
  });

  it("replaceMenuOwners 应 PUT 精确 Owner 路径和仅含 roleIds 的 body", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await replaceMenuOwners(7, [2, 2, 1]);
    expect(http.put).toHaveBeenCalledWith("/api/system/menus/7/owners", { roleIds: [2, 1] });
    const body = (http.put as any).mock.calls[0][1];
    expect(Object.keys(body)).toEqual(["roleIds"]);
  });

  it("Owner API 拒绝非法 menuId 且不发请求", () => {
    expect(() => getMenuOwners(0)).toThrow(RangeError);
    expect(() => replaceMenuOwners(Number.MAX_SAFE_INTEGER + 1, [])).toThrow(RangeError);
    expect(http.get).not.toHaveBeenCalled();
    expect(http.put).not.toHaveBeenCalled();
  });

  it("replaceMenuOwners 拒绝 null/undefined/非数组 roleIds 且不发请求", () => {
    expect(() => replaceMenuOwners(1, null as any)).toThrow(TypeError);
    expect(() => replaceMenuOwners(1, undefined as any)).toThrow(TypeError);
    expect(() => replaceMenuOwners(1, "1" as any)).toThrow(TypeError);
    expect(http.put).not.toHaveBeenCalled();
  });

  it("replaceMenuOwners 拒绝 null、负数、0 与非安全整数元素", () => {
    for (const ids of [[null], [-1], [0], [1.5], [Number.MAX_SAFE_INTEGER + 1]]) {
      expect(() => replaceMenuOwners(1, ids as any)).toThrow(RangeError);
    }
    expect(http.put).not.toHaveBeenCalled();
  });

  it("replaceMenuOwners 在去重后超过 100 个时拒绝", () => {
    const ids = Array.from({ length: 101 }, (_, i) => i + 1);
    expect(() => replaceMenuOwners(1, ids)).toThrow(RangeError);
    expect(http.put).not.toHaveBeenCalled();
  });

  it("replaceMenuOwners 原样发送空数组，表示系统托管", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await replaceMenuOwners(7, []);
    expect(http.put).toHaveBeenCalledWith("/api/system/menus/7/owners", { roleIds: [] });
  });

  it("createMenu 应 POST /api/system/menus 且 body 精确匹配 CreateMenuRequest", async () => {
    (http.post as any).mockResolvedValue(101);
    const id = await createMenu({
      parentId: 0,
      menuName: "菜单管理",
      menuType: 1,
      routerName: "SystemMenu",
      path: "/system/menu",
      permission: "system:menu:list",
      isButton: 0,
      sortNum: 60,
      status: 1,
      remark: "备注"
    });
    expect(id).toBe(101);
    expect(http.post).toHaveBeenCalledWith("/api/system/menus", {
      parentId: 0,
      menuName: "菜单管理",
      menuType: 1,
      routerName: "SystemMenu",
      path: "/system/menu",
      permission: "system:menu:list",
      metaInfo: null,
      isButton: 0,
      sortNum: 60,
      status: 1,
      remark: "备注"
    });
  });

  it("updateMenu 应 PUT /api/system/menus/{menuId} 且绝不含 status/isSystem/审计字段", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await updateMenu(42, {
      parentId: 1,
      menuName: "菜单管理",
      menuType: 1,
      routerName: "SystemMenu",
      path: "/system/menu",
      permission: "system:menu:list",
      isButton: 0,
      sortNum: 60
    });
    expect(http.put).toHaveBeenCalledTimes(1);
    const [url, body] = (http.put as any).mock.calls[0];
    expect(url).toBe("/api/system/menus/42");
    expect(body).not.toHaveProperty("status");
    expect(body).not.toHaveProperty("isSystem");
    expect(body).not.toHaveProperty("deleted");
    expect(body).not.toHaveProperty("creatorId");
    expect(body).not.toHaveProperty("createTime");
    expect(body).not.toHaveProperty("updaterId");
    expect(body).not.toHaveProperty("updateTime");
    expect(body).toMatchObject({
      parentId: 1,
      menuName: "菜单管理",
      menuType: 1,
      routerName: "SystemMenu",
      path: "/system/menu",
      permission: "system:menu:list",
      isButton: 0,
      sortNum: 60
    });
  });

  it("createMenu 的 body 也绝不含 isSystem/deleted/审计字段", async () => {
    (http.post as any).mockResolvedValue(1);
    await createMenu({ parentId: 0, menuName: "目录", menuType: 2, routerName: "Dir", path: "/dir" });
    const body = (http.post as any).mock.calls[0][1];
    expect(body).not.toHaveProperty("isSystem");
    expect(body).not.toHaveProperty("deleted");
    expect(body).not.toHaveProperty("creatorId");
    expect(body).not.toHaveProperty("menuId");
  });

  it("changeMenuStatus 应 PUT /api/system/menus/{menuId}/status 且 body 仅含 status", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await changeMenuStatus(9, 0);
    expect(http.put).toHaveBeenCalledWith("/api/system/menus/9/status", { status: 0 });
  });

  it("deleteMenu 应 DELETE /api/system/menus/{menuId}", async () => {
    (http.delete as any).mockResolvedValue(undefined);
    await deleteMenu(5);
    expect(http.delete).toHaveBeenCalledWith("/api/system/menus/5");
  });

  /* ================= 严格输入校验 ================= */

  it("拒绝非法 menuId（getMenu/updateMenu/changeMenuStatus/deleteMenu）", () => {
    expect(() => getMenu(0)).toThrow(RangeError);
    expect(() => getMenu(1.5)).toThrow(RangeError);
    expect(() => updateMenu(-1, { parentId: 0, menuName: "x", menuType: 1, isButton: 0, sortNum: 0 })).toThrow(RangeError);
    expect(() => changeMenuStatus(0, 1)).toThrow(RangeError);
    expect(() => deleteMenu(Number.NaN)).toThrow(RangeError);
    // 非法输入一律不发请求
    expect(http.get).not.toHaveBeenCalled();
    expect(http.put).not.toHaveBeenCalled();
    expect(http.delete).not.toHaveBeenCalled();
  });

  it("拒绝非法 parentId（负数/非整数）", () => {
    expect(() => createMenu({ parentId: -1, menuName: "x", menuType: 1 })).toThrow(RangeError);
    expect(() => updateMenu(1, { parentId: 1.2, menuName: "x", menuType: 1, isButton: 0, sortNum: 0 })).toThrow(RangeError);
    expect(http.post).not.toHaveBeenCalled();
  });

  it("拒绝非法 menuType（非 1/2/3）", () => {
    expect(() => createMenu({ parentId: 0, menuName: "x", menuType: 4 })).toThrow(RangeError);
    expect(() => createMenu({ parentId: 0, menuName: "x", menuType: 0 })).toThrow(RangeError);
    expect(http.post).not.toHaveBeenCalled();
  });

  it("拒绝非法 status / isButton / sortNum", () => {
    expect(() => changeMenuStatus(1, 2)).toThrow(RangeError);
    expect(() => changeMenuStatus(1, -1)).toThrow(RangeError);
    expect(() => createMenu({ parentId: 0, menuName: "x", menuType: 1, status: 5 })).toThrow(RangeError);
    expect(() => createMenu({ parentId: 0, menuName: "x", menuType: 1, isButton: 2 })).toThrow(RangeError);
    expect(() => updateMenu(1, { parentId: 0, menuName: "x", menuType: 1, isButton: 0, sortNum: 10000 })).toThrow(RangeError);
    expect(() => updateMenu(1, { parentId: 0, menuName: "x", menuType: 1, isButton: 0, sortNum: -1 })).toThrow(RangeError);
    expect(() => createMenu({ parentId: 0, menuName: "x", menuType: 1, sortNum: 1.5 })).toThrow(RangeError);
    expect(http.post).not.toHaveBeenCalled();
    expect(http.put).not.toHaveBeenCalled();
  });

  /* ================= 三种节点类型字段映射与不变量 ================= */

  it("按钮(menuType=3)：permission 必填、routerName/path 必须为空、isButton 强制 1", async () => {
    (http.post as any).mockResolvedValue(1);
    // 合法：按钮 + permission + 空 routerName/path + isButton=1
    await createMenu({
      parentId: 1,
      menuName: "删除菜单",
      menuType: 3,
      permission: "system:menu:delete",
      isButton: 1,
      sortNum: 62
    });
    const body = (http.post as any).mock.calls[0][1];
    expect(body).toMatchObject({
      menuType: 3,
      permission: "system:menu:delete",
      routerName: null,
      path: null,
      isButton: 1
    });

    // 非法：按钮无 permission
    expect(() => createMenu({ parentId: 0, menuName: "b", menuType: 3 })).toThrow(RangeError);
    // 非法：按钮携带 routerName
    expect(() =>
      createMenu({ parentId: 0, menuName: "b", menuType: 3, permission: "a:b", routerName: "X" })
    ).toThrow(RangeError);
    // 非法：按钮携带 path
    expect(() =>
      createMenu({ parentId: 0, menuName: "b", menuType: 3, permission: "a:b", path: "/x" })
    ).toThrow(RangeError);
    // 非法：按钮 isButton 必须为 1
    expect(() =>
      createMenu({ parentId: 0, menuName: "b", menuType: 3, permission: "a:b", isButton: 0 })
    ).toThrow(RangeError);
  });

  it("菜单(menuType=1)：routerName/path 必填且匹配正则、isButton 强制 0", async () => {
    (http.post as any).mockResolvedValue(1);
    await createMenu({
      parentId: 0,
      menuName: "菜单管理",
      menuType: 1,
      routerName: "SystemMenu",
      path: "/system/menu",
      permission: "system:menu:list",
      isButton: 0
    });
    const body = (http.post as any).mock.calls[0][1];
    expect(body).toMatchObject({ menuType: 1, routerName: "SystemMenu", path: "/system/menu", isButton: 0 });

    // 非法：缺 routerName
    expect(() => createMenu({ parentId: 0, menuName: "m", menuType: 1, path: "/x" })).toThrow(RangeError);
    // 非法：routerName 不以字母开头
    expect(() => createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "1abc", path: "/x" })).toThrow(RangeError);
    // 非法：path 不以 / 开头
    expect(() => createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "x/y" })).toThrow(RangeError);
    // 非法：path 含非法字符
    expect(() => createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/x y" })).toThrow(RangeError);
    // 非法：菜单 isButton 必须为 0
    expect(() => createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/x", isButton: 1 })).toThrow(RangeError);
  });

  it("目录(menuType=2)：routerName/path 必填、isButton 固定 0", async () => {
    (http.post as any).mockResolvedValue(1);
    await createMenu({ parentId: 0, menuName: "系统管理", menuType: 2, routerName: "SystemManage", path: "/system" });
    const body = (http.post as any).mock.calls[0][1];
    expect(body).toMatchObject({ menuType: 2, routerName: "SystemManage", path: "/system", isButton: 0 });

    expect(() => createMenu({ parentId: 0, menuName: "d", menuType: 2, routerName: "D" })).toThrow(RangeError);
    expect(() => createMenu({ parentId: 0, menuName: "d", menuType: 2, path: "/d" })).toThrow(RangeError);
  });

  it("permission 格式非法时拒绝（大写/空格/超长）", () => {
    expect(() =>
      createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/x", permission: "System:Menu" })
    ).toThrow(RangeError);
    expect(() =>
      createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/x", permission: "a b" })
    ).toThrow(RangeError);
    expect(() =>
      createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/x", permission: "a".repeat(129) })
    ).toThrow(RangeError);
    // 超长 menuName / routerName / path / remark
    expect(() =>
      createMenu({ parentId: 0, menuName: "n".repeat(65), menuType: 1, routerName: "M", path: "/x" })
    ).toThrow(RangeError);
    expect(() =>
      createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M".repeat(129), path: "/x" })
    ).toThrow(RangeError);
    expect(() =>
      createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/" + "x".repeat(255) })
    ).toThrow(RangeError);
  });

  it("metaInfo 非空时必须为合法 JSON 对象", async () => {
    (http.post as any).mockResolvedValue(1);
    await createMenu({
      parentId: 0,
      menuName: "m",
      menuType: 1,
      routerName: "M",
      path: "/x",
      metaInfo: '{ "icon": "menu" }'
    });
    const body = (http.post as any).mock.calls[0][1];
    expect(body.metaInfo).toBe('{ "icon": "menu" }');

    expect(() =>
      createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/x", metaInfo: "not-json" })
    ).toThrow(RangeError);
    expect(() =>
      createMenu({ parentId: 0, menuName: "m", menuType: 1, routerName: "M", path: "/x", metaInfo: "[1,2]" })
    ).toThrow(RangeError);
  });
});
