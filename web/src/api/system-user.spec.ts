import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  listUsers,
  getUser,
  createUser,
  updateUser,
  deleteUser,
  changeUserStatus,
  resetPassword,
  getUserRoles
} from "./system-user";

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

describe("system-user api", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("listUsers 应 GET /api/system/users 并携带分页与 username 参数", async () => {
    (http.get as any).mockResolvedValue({ records: [], total: 0, current: 1, size: 20, pages: 0 });
    await listUsers({ current: 1, size: 20, username: "alice" });
    expect(http.get).toHaveBeenCalledWith("/api/system/users", {
      params: { current: 1, size: 20, username: "alice" }
    });
  });

  it("listUsers 默认参数 current=1 size=20，username 为空时不发送", async () => {
    (http.get as any).mockResolvedValue({ records: [], total: 0, current: 1, size: 20, pages: 0 });
    await listUsers();
    expect(http.get).toHaveBeenCalledWith("/api/system/users", {
      params: { current: 1, size: 20, username: undefined }
    });
  });

  it("listUsers 拒绝非正安全整数的 current/size", () => {
    expect(() => listUsers({ current: 0, size: 20 })).toThrow(RangeError);
    expect(() => listUsers({ current: 1, size: -5 })).toThrow(RangeError);
    expect(() => listUsers({ current: 1.5, size: 20 })).toThrow(RangeError);
  });

  it("listUsers 拒绝超过最大限制的 size", () => {
    expect(() => listUsers({ current: 1, size: 200 })).toThrow(RangeError);
  });

  it("getUser 应 GET /api/system/users/{userId}", async () => {
    (http.get as any).mockResolvedValue({ userId: 7, username: "alice" });
    await getUser(7);
    expect(http.get).toHaveBeenCalledWith("/api/system/users/7");
  });

  it("createUser 应 POST /api/system/users 并携带完整请求体（含 password）", async () => {
    (http.post as any).mockResolvedValue(42);
    await createUser({ username: "alice", password: "Secret-1", nickname: "Alice" });
    expect(http.post).toHaveBeenCalledWith("/api/system/users", {
      username: "alice",
      password: "Secret-1",
      nickname: "Alice"
    });
  });

  it("updateUser 应 PUT /api/system/users/{userId}", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await updateUser(7, { nickname: "NewNick", email: "a@b.com" });
    expect(http.put).toHaveBeenCalledWith("/api/system/users/7", {
      nickname: "NewNick",
      email: "a@b.com"
    });
  });

  it("deleteUser 应 DELETE /api/system/users/{userId}", async () => {
    (http.delete as any).mockResolvedValue(undefined);
    await deleteUser(7);
    expect(http.delete).toHaveBeenCalledWith("/api/system/users/7");
  });

  it("changeUserStatus 应 PUT /api/system/users/{userId}/status 且 body 为 {status}", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await changeUserStatus(7, 0);
    expect(http.put).toHaveBeenCalledWith("/api/system/users/7/status", { status: 0 });
  });

  it("changeUserStatus 拒绝非 0/1 的状态值", () => {
    expect(() => changeUserStatus(7, 2)).toThrow(RangeError);
    expect(() => changeUserStatus(7, -1)).toThrow(RangeError);
  });

  it("resetPassword 应 PUT /api/system/users/{userId}/password 且 body 为 {password}", async () => {
    (http.put as any).mockResolvedValue(undefined);
    await resetPassword(7, "NewSecret-2");
    expect(http.put).toHaveBeenCalledWith("/api/system/users/7/password", {
      password: "NewSecret-2"
    });
  });

  it("getUserRoles 应 GET /api/system/users/{userId}/roles", async () => {
    (http.get as any).mockResolvedValue([1, 2, 3]);
    await getUserRoles(7);
    expect(http.get).toHaveBeenCalledWith("/api/system/users/7/roles");
  });
});
