import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  listRoles,
  listAllRoles,
  getRole,
  createRole,
  updateRole,
  deleteRole,
  changeRoleStatus,
  getRoleMenuIds,
  assignRoleMenus,
  listMenuTree,
  MAX_ROLE_MENUS
} from "./role";

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

describe("role api", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("listRoles 应 GET /api/system/roles 并携带分页与 roleName 参数", async () => {
    (http.get as any).mockResolvedValue({ records: [], total: 0, current: 1, size: 20, pages: 0 });
    await listRoles({ current: 1, size: 20, roleName: "admin" });
    expect(http.get).toHaveBeenCalledWith("/api/system/roles", {
      params: { current: 1, size: 20, roleName: "admin" }
    });
  });

  it("listRoles 默认参数 current=1 size=20，roleName 为空时不发送", async () => {
    (http.get as any).mockResolvedValue({ records: [], total: 0, current: 1, size: 20, pages: 0 });
    await listRoles();
    expect(http.get).toHaveBeenCalledWith("/api/system/roles", {
      params: { current: 1, size: 20, roleName: undefined }
    });
  });

  it("listRoles 拒绝非正安全整数的 current/size", () => {
    expect(() => listRoles({ current: 0, size: 20 })).toThrow(RangeError);
    expect(() => listRoles({ current: 1, size: -5 })).toThrow(RangeError);
    expect(() => listRoles({ current: 1.5, size: 20 })).toThrow(RangeError);
  });

  it("listRoles 拒绝超过最大限制的 size", () => {
    expect(() => listRoles({ current: 1, size: 200 })).toThrow(RangeError);
  });

  it("listAllRoles 应 GET /api/system/roles/all", async () => {
    (http.get as any).mockResolvedValue([]);
    await listAllRoles();
    expect(http.get).toHaveBeenCalledWith("/api/system/roles/all");
  });

  it("getRole 应 GET /api/system/roles/{roleId}", async () => {
    (http.get as any).mockResolvedValue({ roleId: 3, roleName: "r" });
    await getRole(3);
    expect(http.get).toHaveBeenCalledWith("/api/system/roles/3");
  });

  it("getRole 拒绝非法 roleId", () => {
    expect(() => getRole(0)).toThrow(RangeError);
    expect(() => getRole(-1)).toThrow(RangeError);
    expect(() => getRole(1.5)).toThrow(RangeError);
  });

  it("createRole 应 POST /api/system/roles 并携带请求体（含 menuIds 去重）", async () => {
    (http.post as any).mockResolvedValue(10);
    await createRole({ roleName: "新角色", roleKey: "new_role", roleSort: 1, dataScope: 1, status: 1, remark: "r", menuIds: [100, 100, 200] });
    expect(http.post).toHaveBeenCalledWith("/api/system/roles", {
      roleName: "新角色",
      roleKey: "new_role",
      roleSort: 1,
      dataScope: 1,
      status: 1,
      remark: "r",
      menuIds: [100, 200]
    });
  });

  it("createRole 的 menuIds=null 应透传为 null（不修改菜单）", async () => {
    (http.post as any).mockResolvedValue(11);
    await createRole({ roleName: "x", roleKey: "xk", menuIds: null });
    expect(http.post).toHaveBeenCalledWith("/api/system/roles", expect.objectContaining({ menuIds: null }));
  });

  it("createRole 拒绝含非法 ID 的 menuIds", () => {
    expect(() => createRole({ roleName: "x", roleKey: "xk", menuIds: [1, -2] })).toThrow(RangeError);
    expect(() => createRole({ roleName: "x", roleKey: "xk", menuIds: [0] })).toThrow(RangeError);
  });

  it("createRole 拒绝超过 500 项的 menuIds", () => {
    const big = Array.from({ length: MAX_ROLE_MENUS + 1 }, (_, i) => i + 1);
    expect(() => createRole({ roleName: "x", roleKey: "xk", menuIds: big })).toThrow(RangeError);
  });

  it("updateRole 应 PUT /api/system/roles/{roleId} 且 menuIds=null 表示不修改菜单", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await updateRole(5, { roleName: "编辑", roleKey: "k", menuIds: null });
    expect(http.put).toHaveBeenCalledWith("/api/system/roles/5", {
      roleName: "编辑",
      roleKey: "k",
      roleSort: undefined,
      dataScope: undefined,
      remark: undefined,
      menuIds: null
    });
  });

  it("updateRole 拒绝非法 roleId", () => {
    expect(() => updateRole(0, { roleName: "x", roleKey: "k" })).toThrow(RangeError);
  });

  it("deleteRole 应 DELETE /api/system/roles/{roleId}", async () => {
    (http.delete as any).mockResolvedValue(undefined);
    await deleteRole(5);
    expect(http.delete).toHaveBeenCalledWith("/api/system/roles/5");
  });

  it("changeRoleStatus 应 PUT /api/system/roles/{roleId}/status 且 body 为 {status}", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await changeRoleStatus(5, 0);
    expect(http.put).toHaveBeenCalledWith("/api/system/roles/5/status", { status: 0 });
  });

  it("changeRoleStatus 拒绝非 0/1 的状态值", () => {
    expect(() => changeRoleStatus(5, 2)).toThrow(RangeError);
    expect(() => changeRoleStatus(5, -1)).toThrow(RangeError);
  });

  it("getRoleMenuIds 应 GET /api/system/roles/{roleId}/menus", async () => {
    (http.get as any).mockResolvedValue([1, 2, 3]);
    await getRoleMenuIds(5);
    expect(http.get).toHaveBeenCalledWith("/api/system/roles/5/menus");
  });

  it("assignRoleMenus 应 PUT /api/system/roles/{roleId}/menus 并携带去重后的 menuIds", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await assignRoleMenus(5, { menuIds: [10, 10, 20] });
    expect(http.put).toHaveBeenCalledWith("/api/system/roles/5/menus", { menuIds: [10, 20] });
  });

  it("assignRoleMenus 空数组表示清空菜单", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await assignRoleMenus(5, { menuIds: [] });
    expect(http.put).toHaveBeenCalledWith("/api/system/roles/5/menus", { menuIds: [] });
  });

  it("assignRoleMenus 拒绝非法 roleId", () => {
    expect(() => assignRoleMenus(0, { menuIds: [1] })).toThrow(RangeError);
  });

  it("assignRoleMenus 拒绝含非法 ID 的 menuIds", () => {
    expect(() => assignRoleMenus(5, { menuIds: [1, -1] })).toThrow(RangeError);
  });

  it("listMenuTree 应 GET /api/system/menus/tree（全量菜单树）", async () => {
    (http.get as any).mockResolvedValue([]);
    await listMenuTree();
    expect(http.get).toHaveBeenCalledWith("/api/system/menus/tree");
  });
});