import axios from "axios";
import { getRefreshToken, removeToken, setTokens } from "./auth";

const { VITE_APP_BASE_API } = import.meta.env;
const baseURL = VITE_APP_BASE_API || "";
let refreshInFlight: Promise<string> | null = null;

function redirectToLogin(): void {
  if (typeof location !== "undefined" && location.hash !== "#/login") location.hash = "#/login";
}

async function refreshAccessToken(): Promise<string> {
  const refreshToken = getRefreshToken();
  try {
    if (!refreshToken) throw new Error("missing refresh token");
    const response = await axios.post(`${baseURL}/api/auth/refresh`, { refreshToken }, {
      headers: { "Content-Type": "application/json" }
    });
    const payload = response?.data?.data;
    if (!response?.data?.success || !payload?.accessToken || !payload?.refreshToken) {
      throw new Error("refresh failed");
    }
    setTokens(payload.accessToken, payload.refreshToken);
    if (payload.userInfo && Array.isArray(payload.permissions)) {
      const [{ useUserStoreHook }, { usePermissionStoreHook }] = await Promise.all([
        import("@/store/modules/user"), import("@/store/modules/permission")
      ]);
      const user = useUserStoreHook();
      const permission = usePermissionStoreHook();
      user.setUserInfo(payload.userInfo);
      user.setPermissions(payload.permissions);
      permission.setPermissions(payload.permissions);
      try {
        const menus = await axios.get(`${baseURL}/api/auth/menus`, {
          headers: { Authorization: `Bearer ${payload.accessToken}` }
        });
        if (!menus.data?.success || !Array.isArray(menus.data.data)) throw new Error("menu refresh failed");
        permission.setMenuTree(menus.data.data);
        permission.registerRoutes(permission.buildRoutes(menus.data.data));
      } catch {
        permission.setMenuTree([]);
        permission.registerRoutes([]);
        permission.generated = false;
      }
    }
    return payload.accessToken;
  } catch (error) {
    removeToken();
    redirectToLogin();
    throw error;
  }
}

/** Shares one refresh operation between Axios and fetch callers. */
export function refreshAccessTokenSingleFlight(): Promise<string> {
  if (!refreshInFlight) {
    refreshInFlight = refreshAccessToken().finally(() => { refreshInFlight = null; });
  }
  return refreshInFlight;
}

/** Test-only state reset. */
export function __resetTokenRefreshForTests(): void {
  refreshInFlight = null;
}
