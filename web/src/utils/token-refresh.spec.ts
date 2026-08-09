import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import axios from "axios";
import { getAccessToken } from "./auth";
import { __resetTokenRefreshForTests, refreshAccessTokenSingleFlight } from "./token-refresh";

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
});
