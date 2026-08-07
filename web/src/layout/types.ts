import type { RouteRecordRaw, RouteMeta } from "vue-router";

/** 扩展的路由 meta */
export interface MenuRouteMeta extends RouteMeta {
  title?: string;
  icon?: string;
  menuId?: number;
  permission?: string;
  sortNum?: number;
  affix?: boolean;
}

export type MenuRoute = RouteRecordRaw & {
  meta: MenuRouteMeta;
  children?: MenuRoute[];
};
