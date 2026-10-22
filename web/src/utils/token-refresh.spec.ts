import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import axios from "axios";
import { getAccessToken } from "./auth";
import { __resetTokenRefreshForTests, refreshAccessTokenSingleFlight } from "./token-refresh";

const { user, permission } = vi.hoisted(() => ({
  user: { setUserInfo: vi.fn(), setPermissions: vi.fn() },
  permission: { setPermissions: vi.fn(), setMenuTree: vi.fn(), registerRoutes: vi.fn(), buildRoutes: vi.fn(() => ['new-route']), generated: true }
}));
vi.mock('@/store/modules/user', () => ({ useUserStoreHook: () => user }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStoreHook: () => permission }));

describe("shared token refresh", () => {
  beforeEach(() => {
    sessionStorage.clear();
    __resetTokenRefreshForTests();
  });
  afterEach(() => { vi.restoreAllMocks(); });

  it("uses one refresh request and stores the returned token", async () => {
    sessionStorage.setItem("docbase_refresh_token", "refresh-value");
    const post = vi.spyOn(axios, "post").mockResolvedValue({
      data: { success: true, data: { accessToken: "new-access", refreshToken: "new-refresh" } }
    } as any);
    await expect(Promise.all([refreshAccessTokenSingleFlight(), refreshAccessTokenSingleFlight()]))
      .resolves.toEqual(["new-access", "new-access"]);
    expect(post).toHaveBeenCalledOnce();
    expect(post.mock.calls[0][0]).toContain("/api/auth/refresh");
    expect(post.mock.calls[0][1]).toEqual({ refreshToken: "refresh-value" });
    expect(getAccessToken()).toBe("new-access");
  });

  it("clears authentication when refresh fails", async () => {
    sessionStorage.setItem("docbase_access_token", "old-access");
    sessionStorage.setItem("docbase_refresh_token", "old-refresh");
    vi.spyOn(axios, "post").mockRejectedValue(new Error("failed"));
    await expect(refreshAccessTokenSingleFlight()).rejects.toThrow("failed");
    expect(getAccessToken()).toBeNull();
    expect(location.hash).toBe("#/login");
  });

  it('refreshes personnel, permissions and routes using the new access token', async () => {
    sessionStorage.setItem('docbase_refresh_token', 'old-refresh');
    const info = { userId: 7, organizationId: 20, admin: false };
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { success: true, data: {
      accessToken: 'access-2', refreshToken: 'refresh-2', userInfo: info, permissions: ['system:user:list']
    } } } as any);
    const tree = [{ menuId: 1, path: '/system/user' }];
    const get = vi.spyOn(axios, 'get').mockResolvedValue({ data: { success: true, data: tree } } as any);
    await refreshAccessTokenSingleFlight();
    expect(user.setUserInfo).toHaveBeenCalledWith(info);
    expect(user.setPermissions).toHaveBeenCalledWith(['system:user:list']);
    expect(get.mock.calls[0][1]?.headers).toEqual({ Authorization: 'Bearer access-2' });
    expect(permission.setMenuTree).toHaveBeenCalledWith(tree);
    expect(permission.registerRoutes).toHaveBeenCalledWith(['new-route']);
  });
});
