import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  loginApi,
  refreshApi,
  logoutApi,
  getMeApi,
  getPermissionsApi,
  getMenusApi,
  getAdminSetupStatus,
  setupFirstAdmin
} from "./auth";

// 模拟 request 层
vi.mock("@/utils/request", () => ({
  http: {
    post: vi.fn(),
    get: vi.fn()
  }
}));

import { http } from "@/utils/request";

describe("auth api", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("loginApi 应 POST /api/auth/login 并传明文密码", async () => {
    (http.post as any).mockResolvedValue({ success: true });
    await loginApi({ username: "admin", password: "secret" });
    expect(http.post).toHaveBeenCalledWith("/api/auth/login", {
      username: "admin",
      password: "secret"
    });
    // 密码应是明文（不做 RSA 加密）
    const call = (http.post as any).mock.calls[0];
    expect(call[1].password).toBe("secret");
  });

  it("refreshApi 应 POST /api/auth/refresh 并带 refreshToken 在 body", async () => {
    (http.post as any).mockResolvedValue({ success: true });
    await refreshApi({ refreshToken: "r-123" });
    expect(http.post).toHaveBeenCalledWith("/api/auth/refresh", {
      refreshToken: "r-123"
    });
    // refreshToken 不应出现在 URL 中
    const call = (http.post as any).mock.calls[0];
    expect(call[0]).not.toContain("r-123");
  });

  it("logoutApi 应 POST /api/auth/logout", async () => {
    (http.post as any).mockResolvedValue({ success: true });
    await logoutApi({ refreshToken: "r-123" });
    expect(http.post).toHaveBeenCalledWith("/api/auth/logout", {
      refreshToken: "r-123"
    });
  });

  it("getMeApi 应 GET /api/auth/me", async () => {
    (http.get as any).mockResolvedValue({});
    await getMeApi();
    expect(http.get).toHaveBeenCalledWith("/api/auth/me");
  });

  it("getPermissionsApi 应 GET /api/auth/permissions", async () => {
    (http.get as any).mockResolvedValue({});
    await getPermissionsApi();
    expect(http.get).toHaveBeenCalledWith("/api/auth/permissions");
  });

  it("getMenusApi 应 GET /api/auth/menus", async () => {
    (http.get as any).mockResolvedValue({});
    await getMenusApi();
    expect(http.get).toHaveBeenCalledWith("/api/auth/menus");
  });
  it("getAdminSetupStatus uses the anonymous setup endpoint without global errors", async () => {
    (http.get as any).mockResolvedValue({ required: true, enabled: true });
    await getAdminSetupStatus();
    expect(http.get).toHaveBeenCalledWith("/api/auth/setup", {
      skipGlobalErrorMessage: true
    });
  });

  it("setupFirstAdmin only sends the explicit first-admin fields", async () => {
    (http.post as any).mockResolvedValue(1);
    const request = {
      setupKey: "operator-setup-key-at-least-32-chars",
      username: "admin",
      nickname: "Administrator",
      password: "StrongPass!123"
    };
    await setupFirstAdmin(request);
    expect(http.post).toHaveBeenCalledWith("/api/auth/setup", request);
  });
});
