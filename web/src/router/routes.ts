/**
 * 静态路由（不参与菜单、始终存在）。
 */
import type { RouteRecordRaw } from "vue-router";

const Layout = () => import("@/layout/index.vue");

/** 根重定向 */
export const ROOT_ROUTE: RouteRecordRaw = {
  path: "/",
  redirect: "/home"
};

/** 登录页 */
export const LOGIN_ROUTE: RouteRecordRaw = {
  path: "/login",
  name: "Login",
  component: () => import("@/views/login/index.vue"),
  meta: { title: "登录" }
};

/** 内嵌布局下的静态页面 */
export const INNER_ROUTES: RouteRecordRaw[] = [
  {
    path: "/home",
    name: "Home",
    component: () => import("@/views/home/index.vue"),
    meta: { title: "首页", affix: true }
  }
];

/** 错误页（不显示在菜单） */
export const ERROR_ROUTES: RouteRecordRaw[] = [
  {
    path: "/error/401",
    name: "Error401",
    component: () => import("@/views/error/401.vue"),
    meta: { title: "401" }
  },
  {
    path: "/error/403",
    name: "Error403",
    component: () => import("@/views/error/403.vue"),
    meta: { title: "403" }
  },
  {
    path: "/error/404",
    name: "Error404",
    component: () => import("@/views/error/404.vue"),
    meta: { title: "404" }
  },
  {
    path: "/error/500",
    name: "Error500",
    component: () => import("@/views/error/500.vue"),
    meta: { title: "500" }
  },
  // 兜底匹配，指向 404
  {
    path: "/:pathMatch(.*)*",
    name: "NotFound",
    component: () => import("@/views/error/404.vue"),
    meta: { title: "404" }
  }
];

/** 需要包裹在 Layout 下的静态子路由 */
export const LAYOUT_ROUTES: RouteRecordRaw[] = [
  {
    path: "/",
    name: "RootLayout",
    component: Layout,
    redirect: "/home",
    children: [...INNER_ROUTES, ...ERROR_ROUTES]
  }
];

/** 白名单（无需登录） */
export const WHITE_LIST = ["/login"];
