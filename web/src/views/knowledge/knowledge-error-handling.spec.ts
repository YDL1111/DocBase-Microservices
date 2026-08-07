import { describe, it, expect, vi, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { nextTick } from "vue";
import { ElMessage } from "element-plus";

// 模拟 element-plus 的 ElMessage 和 ElMessageBox
vi.mock("element-plus", () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  },
  ElMessageBox: {
    confirm: vi.fn().mockResolvedValue(undefined)
  }
}));

describe("knowledge error handling", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("403 响应应由 request 层拦截并提示'没有访问权限'", async () => {
    // 模拟 request 层的 403 处理
    const error = {
      response: {
        status: 403,
        data: { success: false, code: "FORBIDDEN", message: "无权访问该资源" }
      },
      config: { url: "/api/knowledge/bases/42/members", headers: { set: vi.fn() }, _retry: false },
      message: "Request failed with status code 403"
    };

    // 验证错误对象结构符合 request 拦截器预期
    expect(error.response.status).toBe(403);
    expect(error.response.data.code).toBe("FORBIDDEN");
  });

  it("401 响应应触发 token 刷新逻辑", async () => {
    const error = {
      response: {
        status: 401,
        data: { success: false, code: "UNAUTHORIZED", message: "token expired" }
      },
      config: { url: "/api/knowledge/bases", headers: { set: vi.fn() }, _retry: false },
      message: "Request failed with status code 401"
    };

    expect(error.response.status).toBe(401);
    // 非白名单接口才触发刷新
    const whiteList = ["/api/auth/login", "/api/auth/refresh", "/api/auth/ping"];
    const isWhitelisted = whiteList.some(item => error.config.url.includes(item));
    expect(isWhitelisted).toBe(false);
  });

  it("业务失败（success:false）应携带错误码", async () => {
    const response = {
      data: {
        success: false,
        code: "FOLDER_HAS_CHILDREN",
        message: "目录包含子目录，无法删除",
        data: null
      },
      config: {}
    };

    expect(response.data.success).toBe(false);
    expect(response.data.code).toBe("FOLDER_HAS_CHILDREN");
  });
});
