import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  getAccessToken,
  getRefreshToken,
  getUserInfo,
  setLoginResult,
  setTokens,
  setUserInfo,
  removeToken,
  isAuthenticated,
  formatToken
} from "./auth";
import {
  storage,
  TOKEN_KEY,
  REFRESH_TOKEN_KEY,
  USER_INFO_KEY
} from "./storage";
import type { UserInfo } from "@/api/types";

describe("auth token storage", () => {
  const sampleUser: UserInfo = {
    userId: 1,
    username: "admin",
    nickname: "管理员",
    email: "admin@docbase.io",
    phoneNumber: "13800000000",
    admin: true
  };

  beforeEach(() => {
    sessionStorage.clear();
  });

  afterEach(() => {
    sessionStorage.clear();
  });

  it("初始状态：未登录", () => {
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(getUserInfo()).toBeNull();
    expect(isAuthenticated()).toBe(false);
  });

  it("setLoginResult 应同时写入 access/refresh token 与用户信息", () => {
    setLoginResult({
      accessToken: "access-123",
      refreshToken: "refresh-456",
      userInfo: sampleUser
    });
    expect(getAccessToken()).toBe("access-123");
    expect(getRefreshToken()).toBe("refresh-456");
    expect(getUserInfo()?.username).toBe("admin");
    expect(isAuthenticated()).toBe(true);
  });

  it("setTokens 应只更新 token，不影响用户信息", () => {
    setLoginResult({
      accessToken: "a",
      refreshToken: "r",
      userInfo: sampleUser
    });
    setTokens("a2", "r2");
    expect(getAccessToken()).toBe("a2");
    expect(getRefreshToken()).toBe("r2");
    expect(getUserInfo()?.username).toBe("admin");
  });

  it("removeToken 应清除所有认证态", () => {
    setLoginResult({
      accessToken: "a",
      refreshToken: "r",
      userInfo: sampleUser
    });
    removeToken();
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(getUserInfo()).toBeNull();
    expect(isAuthenticated()).toBe(false);
  });

  it("formatToken 应拼上 Bearer 前缀", () => {
    expect(formatToken("abc")).toBe("Bearer abc");
  });

  it("token 不应被写入 localStorage（防持久化泄露）", () => {
    setLoginResult({
      accessToken: "a",
      refreshToken: "r",
      userInfo: sampleUser
    });
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(USER_INFO_KEY)).toBeNull();
  });
});
