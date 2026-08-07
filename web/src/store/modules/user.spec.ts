import { describe, it, expect, beforeEach, vi } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useUserStore } from "./user";
import * as auth from "@/utils/auth";

vi.mock("@/utils/auth", () => ({
  getUserInfo: vi.fn(),
  setUserInfo: vi.fn(),
  removeToken: vi.fn()
}));

describe("user store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("admin 用户应拥有全部权限", () => {
    const store = useUserStore();
    store.setUserInfo({
      userId: 1,
      username: "admin",
      nickname: "管理员",
      email: "",
      phoneNumber: "",
      admin: true
    });
    expect(store.hasPermission("any:code")).toBe(true);
    expect(store.hasPermission(["a", "b"])).toBe(true);
  });

  it("非 admin 用户应按 permissions 集合判断", () => {
    const store = useUserStore();
    store.setUserInfo({
      userId: 2,
      username: "user",
      nickname: "普通用户",
      email: "",
      phoneNumber: "",
      admin: false
    });
    store.setPermissions(["system:user:view"]);
    expect(store.hasPermission("system:user:view")).toBe(true);
    expect(store.hasPermission("system:user:create")).toBe(false);
    // 数组模式：全部满足才为 true
    expect(store.hasPermission(["system:user:view"])).toBe(true);
    expect(
      store.hasPermission(["system:user:view", "system:user:create"])
    ).toBe(false);
  });

  it("clearUser 应清空所有态并调用 removeToken", () => {
    const store = useUserStore();
    store.setUserInfo({
      userId: 1,
      username: "admin",
      nickname: "管理员",
      email: "",
      phoneNumber: "",
      admin: true
    });
    store.clearUser();
    expect(store.userId).toBeNull();
    expect(store.username).toBe("");
    expect(store.permissions).toEqual([]);
    expect(auth.removeToken).toHaveBeenCalled();
  });

  it("displayName 应优先返回 nickname", () => {
    const store = useUserStore();
    store.setUserInfo({
      userId: 1,
      username: "admin",
      nickname: "管理员",
      email: "",
      phoneNumber: "",
      admin: false
    });
    expect(store.displayName).toBe("管理员");
  });
});
