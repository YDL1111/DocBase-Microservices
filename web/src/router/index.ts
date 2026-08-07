/**
 * 路由实例与导航守卫。
 *
 * 守卫职责：
 *  - 未登录访问非白名单 → 跳登录；
 *  - 已登录：首次进入（刷新）时若尚未生成动态路由，则拉取菜单 + 权限并注册；
 *  - 已登录访问 /login → 回首页。
 */
import { createRouter, createWebHashHistory } from "vue-router";
import type { Router } from "vue-router";
import { WHITE_LIST, LOGIN_ROUTE, LAYOUT_ROUTES, ROOT_ROUTE } from "./routes";
import { isAuthenticated } from "@/utils/auth";
import progress from "@/utils/progress";
import { useUserStoreHook } from "@/store/modules/user";
import { usePermissionStoreHook } from "@/store/modules/permission";
import {
  getMenusApi,
  getPermissionsApi,
  getMeApi
} from "@/api/auth";
import { message } from "@/utils/message";

const { VITE_ROUTER_HISTORY } = import.meta.env;
const history =
  VITE_ROUTER_HISTORY === "h5"
    ? () => import("vue-router").then(m => m.createWebHistory())
    : createWebHashHistory;

export const router: Router = createRouter({
  history: createWebHashHistory(),
  routes: [ROOT_ROUTE, LOGIN_ROUTE, ...LAYOUT_ROUTES],
  strict: true,
  scrollBehavior() {
    return { left: 0, top: 0 };
  }
});

/** 拉取菜单 + 权限，注册动态路由 */
async function bootstrapDynamicRoutes(): Promise<void> {
  const permission = usePermissionStoreHook();
  // v-auth 指令统一从 userStore.permissions 读取，这里必须同步更新
  const user = useUserStoreHook();
  try {
    const [menusRes, permsRes] = await Promise.all([
      getMenusApi(),
      getPermissionsApi()
    ]);
    const perms = Array.isArray(permsRes) ? permsRes : [];
    permission.setMenuTree(Array.isArray(menusRes) ? menusRes : []);
    permission.setPermissions(perms);
    // 同步写入 userStore，保证刷新后 v-auth 仍可用
    user.setPermissions(perms);
    const routes = permission.buildRoutes(permission.menuTree);
    permission.registerRoutes(routes);
  } catch (e) {
    // 菜单加载失败不阻塞登录，仅提示
    message.warning("菜单加载失败，请刷新重试");
  }
}

router.beforeEach(async (to, from, next) => {
  progress.start();
  document.title = to.meta?.title
    ? `${to.meta.title} | DocBase`
    : "DocBase";

  const user = useUserStoreHook();

  if (isAuthenticated()) {
    // 已登录但尚未拉取用户态（刷新页面场景）
    if (!user.isLoggedIn) {
      try {
        const me = await getMeApi();
        user.setUserInfo(me);
      } catch {
        // me 失败时保守处理：退回登录
        user.clearUser();
        next({ path: "/login", query: { redirect: to.fullPath } });
        return;
      }
    }

    if (to.path === "/login") {
      next({ path: "/home" });
      return;
    }

    // 首次（刷新）需要重新注册动态路由
    const permission = usePermissionStoreHook();
    if (!permission.generated) {
      await bootstrapDynamicRoutes();
      // 重新进入当前路由，确保新注册的路由命中
      next({ ...to, replace: true });
      return;
    }

    next();
  } else {
    if (WHITE_LIST.includes(to.path)) {
      next();
    } else {
      next({ path: "/login", query: { redirect: to.fullPath } });
    }
  }
});

router.afterEach(() => {
  progress.done();
});

/** 重置路由（登出时） */
export function resetRouter(): void {
  // 删除所有带 menuId 的自定义路由
  router.getRoutes().forEach(route => {
    if (route.name && (route.meta as any)?.menuId) {
      router.removeRoute(route.name as string);
    }
  });
}

export default router;
