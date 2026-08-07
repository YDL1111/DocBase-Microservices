/**
 * 统一 sessionStorage 封装。
 *
 * Token 存储策略最终方案：
 *  - accessToken、refreshToken、userInfo 全部存入 sessionStorage（非 localStorage）。
 *  - 选择 sessionStorage 的原因：
 *    1. 页面关闭即清除，降低持久化泄露风险（localStorage 持久保存，XSS 可长期读取）；
 *    2. 不受 CSRF 影响（不会被浏览器自动带上请求，区别于 Cookie）；
 *    3. 刷新页面后可恢复登录态（避免纯内存方案每次刷新都要重新登录）；
 *    4. refreshToken 绝不出现在 URL 中，仅通过请求体传递。
 *  - 敏感信息（如 refreshToken）不写入 localStorage，不写入非 httpOnly Cookie。
 */
export const TOKEN_KEY = "docbase_access_token";
export const REFRESH_TOKEN_KEY = "docbase_refresh_token";
export const USER_INFO_KEY = "docbase_user_info";

function safeParse<T>(raw: string | null, fallback: T | null): T | null {
  if (raw == null) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

export const storage = {
  set(key: string, value: unknown): void {
    if (value == null) {
      sessionStorage.removeItem(key);
      return;
    }
    sessionStorage.setItem(
      key,
      typeof value === "string" ? value : JSON.stringify(value)
    );
  },

  get<T>(key: string): T | null {
    return safeParse<T>(sessionStorage.getItem(key), null);
  },

  getString(key: string): string | null {
    const v = sessionStorage.getItem(key);
    return v == null || v === "null" || v === "undefined" ? null : v;
  },

  remove(key: string): void {
    sessionStorage.removeItem(key);
  },

  clearAuth(): void {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(REFRESH_TOKEN_KEY);
    sessionStorage.removeItem(USER_INFO_KEY);
  }
};
