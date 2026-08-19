import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from "axios";
import { getAccessToken } from "./auth";
import { message } from "./message";
import progress from "./progress";
import { refreshAccessTokenSingleFlight } from "./token-refresh";

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
const WHITELIST = [
  "/api/auth/login",
  "/api/auth/refresh",
  "/api/auth/ping",
  "/api/auth/setup"
];

function isWhiteListed(url: string | undefined): boolean {
  return !!url && WHITELIST.some(item => url.includes(item));
}

const service: AxiosInstance = axios.create({
  baseURL,
  timeout: 15000,
  headers: {
    Accept: "application/json, text/plain, */*",
    "X-Requested-With": "XMLHttpRequest"
  }
});

service.interceptors.request.use(
  (config: CustomAxiosRequestConfig) => {
    progress.start();
    if (!isWhiteListed(config.url)) {
      const token = getAccessToken();
      if (token) config.headers.set("Authorization", `Bearer ${token}`);
    }
    return config;
  },
  error => {
    progress.done();
    return Promise.reject(error);
  }
);

service.interceptors.response.use(
  (response: AxiosResponse) => {
    progress.done();
    const { data } = response;
    if (data instanceof Blob) return data as any;

    const skipGlobalErrorMessage = (response.config as CustomAxiosRequestConfig)
      .skipGlobalErrorMessage;
    if (data == null || typeof data.success !== "boolean") {
      if (!skipGlobalErrorMessage) message.error("服务器返回数据结构异常");
      return Promise.reject(new Error("malformed response"));
    }
    if (!data.success) {
      if (!skipGlobalErrorMessage) message.error(data.message || "请求失败");
      return Promise.reject(new Error(data.code || data.message || "business error"));
    }
    return data.data as any;
  },
  async error => {
    progress.done();
    const originalConfig = error?.config as CustomAxiosRequestConfig | undefined;
    const status = error?.response?.status;

    if (status === 401 && originalConfig && !isWhiteListed(originalConfig.url) && !originalConfig._retry) {
      originalConfig._retry = true;
      try {
        const newToken = await refreshAccessTokenSingleFlight();
        originalConfig.headers.set("Authorization", `Bearer ${newToken}`);
        return service(originalConfig);
      } catch (refreshError) {
        return Promise.reject(refreshError);
      }
    }

    if (!originalConfig?.skipGlobalErrorMessage) {
      if (status >= SERVICE_UNAVAILABLE) message.error("服务暂不可用，请稍后重试");
      else if (status === 403) message.error("没有访问权限");
      else if (status === 404) message.error("请求资源不存在");
      else if (status != null && status < 500 && status >= 400) {
        message.error(error?.response?.data?.message || "请求异常");
      }
    }
    return Promise.reject(error);
  }
);

export const http = {
  get<T = unknown>(url: string, config?: RequestConfig): Promise<T> {
    return service.get(url, config) as unknown as Promise<T>;
  },
  post<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<T> {
    return service.post(url, data, config) as unknown as Promise<T>;
  },
  put<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<T> {
    return service.put(url, data, config) as unknown as Promise<T>;
  },
  delete<T = unknown>(url: string, config?: RequestConfig): Promise<T> {
    return service.delete(url, config) as unknown as Promise<T>;
  }
};

export { service };
