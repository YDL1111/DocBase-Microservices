import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import axios from "axios";

/**
 * 请求层测试：
 *  - 请求拦截：白名单不带头、非白名单带 Bearer；
 *  - 响应拦截：剥离 ApiResponse 外壳、直接返回 data 字段；
 *  - 刷新风暴防护：通过 mock 底层 service 验证并发 401 只触发一次 refresh，
 *    且排队请求被真正重放（service(config) 被调用）。
 */
describe("request layer - interceptors", () => {
  let requestHandler: any;
  let responseHandler: any;

  beforeEach(() => {
    sessionStorage.clear();
    vi.clearAllMocks();

    vi.spyOn(axios, "create").mockReturnValue({
      interceptors: {
        request: {
          use: vi.fn((onFulfilled, onRejected) => {
            requestHandler = onFulfilled;
          })
        },
        response: {
          use: vi.fn((onFulfilled, onRejected) => {
            responseHandler = onFulfilled;
          })
        }
      },
      get: vi.fn(),
      post: vi.fn()
    } as any);
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it("axios.create 应被初始化", async () => {
    await import("./request");
    expect(axios.create).toHaveBeenCalledOnce();
  });

  describe("请求拦截器", () => {
    it("白名单接口不应设置 Authorization 头", async () => {
      await import("./request");
      const config = { url: "/api/auth/login", headers: { set: vi.fn() } };
      const result = await requestHandler(config);
      expect(result).toBe(config);
      expect(config.headers.set).not.toHaveBeenCalled();
    });

    it("refresh 接口（白名单）不应设置 Authorization 头", async () => {
      await import("./request");
      const config = { url: "/api/auth/refresh", headers: { set: vi.fn() } };
      const result = await requestHandler(config);
      expect(config.headers.set).not.toHaveBeenCalled();
    });

    it("非白名单接口应附带 Bearer token", async () => {
      // 模拟实际 setLoginResult 的存储行为：纯字符串直接存
      sessionStorage.setItem("docbase_access_token", "my-token");
      await import("./request");

      const config = {
        url: "/api/auth/menus",
        headers: { set: vi.fn() }
      };
      await requestHandler(config);
      expect(config.headers.set).toHaveBeenCalledWith(
        "Authorization",
        "Bearer my-token"
      );
      sessionStorage.clear();
    });
  });

  describe("响应拦截器", () => {
    it("成功响应应剥离外壳，返回 data 字段", async () => {
      await import("./request");
      const response = {
        data: {
          success: true,
          code: "OK",
          message: "success",
          data: { list: [1, 2, 3] }
        },
        config: {}
      };
      const result = await responseHandler(response);
      expect(result).toEqual({ list: [1, 2, 3] });
    });

    it("业务失败应 reject", async () => {
      await import("./request");
      const response = {
        data: {
          success: false,
          code: "ERR",
          message: "操作失败",
          data: null
        },
        config: {}
      };
      await expect(responseHandler(response)).rejects.toThrow();
    });

    it("Blob 响应应直接返回", async () => {
      await import("./request");
      const blob = new Blob(["test"], { type: "application/octet-stream" });
      const response = { data: blob, config: {} };
      const result = await responseHandler(response);
      expect(result).toBe(blob);
    });
  });
});

/**
 * 刷新风暴防护的端到端行为测试。
 *
 * 通过正确 mock axios（axios 实例本身是可调用的函数，同时拥有 interceptors/get/post），
 * 验证：多个并发 401 只触发一次 refresh，且排队请求通过 service(config) 被真正重放。
 */
describe("request layer - refresh storm prevention (e2e)", () => {
  let mockService: ReturnType<typeof vi.fn>;
  let errorHandler: any;

  beforeEach(() => {
    sessionStorage.clear();
    vi.resetModules();
    vi.clearAllMocks();

    // 预置 token，模拟已登录态
    sessionStorage.setItem("docbase_access_token", "old-token");
    sessionStorage.setItem("docbase_refresh_token", "old-refresh");

    // 可调用的 axios 实例 mock（axios.create 返回的是函数）
    mockService = vi.fn();

    const mockAxiosInstance = Object.assign(mockService, {
      interceptors: {
        request: { use: vi.fn() },
        response: {
          use: vi.fn((_onFulfilled: any, onRejected: any) => {
            errorHandler = onRejected;
          })
        }
      },
      get: mockService,
      post: mockService
    });

    vi.spyOn(axios, "create").mockReturnValue(mockAxiosInstance as any);

    // 模拟刷新接口（axios.post 直接调用，不走拦截器），并计数
    vi.spyOn(axios, "post").mockImplementation(async (url: string) => {
      if (typeof url === "string" && url.includes("/api/auth/refresh")) {
        return {
          data: {
            success: true,
            code: "OK",
            data: { accessToken: "new-token", refreshToken: "new-refresh" }
          }
        };
      }
      return { data: { success: true, data: {} } };
    });
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
    vi.resetModules();
  });

  it("并发 401 应只触发一次 refresh，排队请求通过 service(config) 真正重放", async () => {
    let refreshCount = 0;

    // 包装 axios.post 以计数 refresh 调用
    const spy = vi.spyOn(axios, "post").mockImplementation(async (url: string) => {
      if (typeof url === "string" && url.includes("/api/auth/refresh")) {
        refreshCount++;
      }
      return {
        data: {
          success: true,
          code: "OK",
          data: { accessToken: "new-token", refreshToken: "new-refresh" }
        }
      };
    });

    // 强制重新导入模块（绕过缓存），触发拦截器注册
    await vi.importActual("./request");

    expect(errorHandler).toBeDefined();

    // 构造 401 错误
    const makeError = (url: string) => ({
      response: { status: 401, data: { success: false, message: "expired" } },
      config: {
        url,
        headers: { set: vi.fn() },
        _retry: false
      },
      message: "Request failed with status code 401"
    });

    // 模拟 service 重放返回成功响应（经过拦截器会剥离外壳）
    mockService.mockResolvedValue({
      data: { success: true, data: { result: "ok" } },
      config: {}
    });

    // 触发两个并发的 401
    const p1 = errorHandler(makeError("/api/orders")).catch(() => {});
    const p2 = errorHandler(makeError("/api/products")).catch(() => {});

    // 等待刷新和重放完成
    await new Promise(resolve => setTimeout(resolve, 200));
    await Promise.allSettled([p1, p2]);

    // 核心断言：refresh 只被调用一次
    expect(refreshCount).toBe(1);

    // service(config) 应被调用重放排队请求
    // 注意：第一个请求在刷新成功后也会通过 service(originalConfig) 重放
    expect(mockService.mock.calls.length).toBeGreaterThanOrEqual(2);
  });
});
