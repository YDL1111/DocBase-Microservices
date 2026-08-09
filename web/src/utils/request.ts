/**
 * 统一 HTTP 请求层。
 *
 * 核心能力：
 *  1. 统一 baseURL、超时、请求头；
 *  2. 请求拦截：自动附带 Authorization: Bearer <accessToken>，白名单接口除外；
 *  3. 响应拦截：剥离 ApiResponse 外壳，业务错误统一提示；
 *  4. 401 自动刷新 token，并【防止并发刷新风暴】：
 *     - 同一时刻只允许一个刷新请求正在进行（isRefreshing 标志）；
 *     - 期间所有后续失败请求进入队列（requests 数组），等刷新完成后用新 token 重放；
 *     - 刷新失败则清空认证态并跳转到登录页。
 *
 * 安全约束：
 *  - 不在日志/错误提示中输出 token 原文；
 *  - refreshToken 只出现在 /api/auth/refresh 的请求体中，绝不出现在 URL。
 */
import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from "axios";
import { getAccessToken, getRefreshToken, setTokens, removeToken } from "./auth";
import { message } from "./message";
import progress from "./progress";

/** 扩展请求配置：加入自定义 _retry 标记 */
export interface RequestConfig extends AxiosRequestConfig {
  /** The caller owns user-facing failure feedback for this request. */
  skipGlobalErrorMessage?: boolean;
}

interface CustomAxiosRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
  skipGlobalErrorMessage?: boolean;
}

const { VITE_APP_BASE_API } = import.meta.env;
const baseURL = VITE_APP_BASE_API || "";

const SERVICE_UNAVAILABLE = 503;

/** 不需要携带 token 的接口白名单 */
const WHITELIST = [
  "/api/auth/login",
  "/api/auth/refresh",
  "/api/auth/ping"
];

/** 判断 url 是否命中白名单 */
function isWhiteListed(url: string | undefined): boolean {
  if (!url) return false;
  return WHITELIST.some(item => url.includes(item));
}

const service: AxiosInstance = axios.create({
  baseURL,
  timeout: 15000,
  headers: {
    Accept: "application/json, text/plain, */*",
    "X-Requested-With": "XMLHttpRequest"
  }
});

/* ---------------- 刷新风暴防护状态（模块级单例） ---------------- */

let isRefreshing = false;
type PendingTask = {
  config: CustomAxiosRequestConfig;
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
};
let pendingQueue: PendingTask[] = [];

/** 刷新完成后真正重放队列中的请求，把业务结果 resolve 给调用方 */
function retryPendingRequests(newToken: string): void {
  // 复制后清空，防止重放过程中新入队的任务被误清
  const queue = pendingQueue;
  pendingQueue = [];
  queue.forEach(({ config, resolve, reject }) => {
    config.headers.set("Authorization", `Bearer ${newToken}`);
    // 真正重放请求，将业务响应（或错误）传递给调用方
    service(config).then(resolve).catch(reject);
  });
}

/** 拒绝队列中全部请求 */
function rejectPendingRequests(err: unknown): void {
  pendingQueue.forEach(({ reject }) => reject(err));
  pendingQueue = [];
}

/* ---------------- 请求拦截 ---------------- */

service.interceptors.request.use(
  (config: CustomAxiosRequestConfig) => {
    progress.start();
    if (isWhiteListed(config.url)) {
      return config;
    }
    const token = getAccessToken();
    if (token) {
      config.headers.set("Authorization", `Bearer ${token}`);
    }
    return config;
  },
  error => {
    progress.done();
    return Promise.reject(error);
  }
);

/* ---------------- 响应拦截 ---------------- */

service.interceptors.response.use(
  (response: AxiosResponse) => {
    progress.done();
    const { data } = response;

    // 二进制流直接返回
    if (data instanceof Blob) {
      return data as any;
    }

    // 未按 ApiResponse 外壳返回，视为异常
    const skipGlobalErrorMessage = (response.config as CustomAxiosRequestConfig)
      .skipGlobalErrorMessage;
    if (data == null || typeof data.success !== "boolean") {
      if (!skipGlobalErrorMessage) message.error("服务器返回数据结构异常");
      return Promise.reject(new Error("malformed response"));
    }

    // 业务失败
    if (!data.success) {
      if (!skipGlobalErrorMessage) message.error(data.message || "请求失败");
      return Promise.reject(
        new Error(data.code || data.message || "business error")
      );
    }

    // 成功：剥离外壳，返回 data 字段
    return data.data as any;
  },
  async error => {
    progress.done();

    const originalConfig = error?.config as
      | CustomAxiosRequestConfig
      | undefined;
    const status = error?.response?.status;

    // 仅对非刷新接口、且非白名单的 401 触发刷新逻辑
    if (
      status === 401 &&
      originalConfig &&
      !isWhiteListed(originalConfig.url) &&
      !originalConfig._retry
    ) {
      // 已经在刷新中 → 把当前请求挂入队列，避免并发刷新风暴
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          pendingQueue.push({
            config: originalConfig,
            resolve,
            reject
          });
        });
      }

      originalConfig._retry = true;
      isRefreshing = true;

      try {
        const refreshToken = getRefreshToken();
        if (!refreshToken) {
          throw new Error("missing refresh token");
        }
        // 直接调用 axios 避免再次进入拦截器造成递归
        const resp = await axios.post(
          `${baseURL}/api/auth/refresh`,
          { refreshToken },
          { headers: { "Content-Type": "application/json" } }
        );
        const payload = resp?.data?.data;
        if (!resp?.data?.success || !payload?.accessToken) {
          throw new Error("refresh failed");
        }
        setTokens(payload.accessToken, payload.refreshToken);

        // 用新 token 重放队列与当前请求
        retryPendingRequests(payload.accessToken);
        originalConfig.headers.set(
          "Authorization",
          `Bearer ${payload.accessToken}`
        );
        return service(originalConfig);
      } catch (e) {
        // 刷新失败：清空队列与认证态，跳转登录
        rejectPendingRequests(e);
        removeToken();
        if (location.hash !== "#/login") {
          location.hash = "#/login";
        }
        return Promise.reject(e);
      } finally {
        isRefreshing = false;
      }
    }

    // 其他 HTTP 错误
    if (!originalConfig?.skipGlobalErrorMessage) {
      if (status >= SERVICE_UNAVAILABLE) {
        message.error("服务暂不可用，请稍后重试");
      } else if (status === 403) {
        message.error("没有访问权限");
      } else if (status === 404) {
        message.error("请求资源不存在");
      } else if (status != null && status < 500 && status >= 400) {
        message.error(error?.response?.data?.message || "请求异常");
      }
    }

    return Promise.reject(error);
  }
);

/* ---------------- 对外方法 ---------------- */

export const http = {
  get<T = unknown>(url: string, config?: RequestConfig): Promise<T> {
    return service.get(url, config) as unknown as Promise<T>;
  },
  post<T = unknown>(
    url: string,
    data?: unknown,
    config?: RequestConfig
  ): Promise<T> {
    return service.post(url, data, config) as unknown as Promise<T>;
  },
  put<T = unknown>(
    url: string,
    data?: unknown,
    config?: RequestConfig
  ): Promise<T> {
    return service.put(url, data, config) as unknown as Promise<T>;
  },
  delete<T = unknown>(url: string, config?: RequestConfig): Promise<T> {
    return service.delete(url, config) as unknown as Promise<T>;
  }
};

export { service };
