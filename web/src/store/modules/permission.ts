/**
 * 菜单 / 动态路由 store。
 *
 * 后端 /api/auth/menus 返回 MenuNode 树（无组件信息），
 * 这里将其转换为前端路由并注册到 router。
 *
 * 当前阶段（Phase 1）只迁移基础设施，没有真实业务页面，
 * 因此动态路由会指向占位组件，待 Phase 2 迁移业务页面时再替换。
 */
import { defineStore } from "pinia";
import { store } from "@/store";
import type { RouteRecordRaw } from "vue-router";
import { router } from "@/router";
import type { MenuNode } from "@/api/types";

export interface PermissionState {
  menuTree: MenuNode[];
  dynamicRoutes: RouteRecordRaw[];
  permissions: string[];
  generated: boolean;
}

/** 占位组件：业务页面尚未迁移时的兜底页 */
const PlaceholderView = () => import("@/views/placeholder/index.vue");

export const usePermissionStore = defineStore({
  id: "docbase-permission",
  state: (): PermissionState => ({
    menuTree: [],
    dynamicRoutes: [],
    permissions: [],
    generated: false
  }),
  getters: {
    menus: (state): MenuNode[] => state.menuTree
  },
  actions: {
    /** 把 MenuNode 树转换为路由配置 */
    buildRoutes(nodes: MenuNode[]): RouteRecordRaw[] {
      const routes: RouteRecordRaw[] = [];
      for (const node of nodes) {
        if (node.isButton === 1) continue; // 按钮级权限不进路由
        const route = this.nodeToRoute(node);
        if (node.children && node.children.length > 0) {
          route.children = this.buildRoutes(node.children);
        }
        routes.push(route);
      }
      return routes;
    },

    nodeToRoute(node: MenuNode): RouteRecordRaw {
      return {
        path: node.path,
        name: node.routerName || `menu-${node.menuId}`,
        component: PlaceholderView,
        meta: {
          title: node.menuName,
          menuId: node.menuId,
          permission: node.permission,
          sortNum: node.sortNum
        }
      } as RouteRecordRaw;
    },

    /** 注册动态路由到根布局下 */
    registerRoutes(routes: RouteRecordRaw[]): void {
      // 先清理旧的自定义动态路由（保留静态）
      const existing = router.getRoutes();
      existing.forEach(route => {
        if (route.name && (route.meta as any)?.menuId && router.hasRoute(route.name as string)) {
          router.removeRoute(route.name as string);
        }
      });
      routes.forEach(r => {
        // 挂在命名父路由 RootLayout 下作为子路由
        router.addRoute("RootLayout", r);
      });
      this.dynamicRoutes = routes;
      this.generated = true;
    },

    setMenuTree(tree: MenuNode[]) {
      this.menuTree = tree;
    },

    setPermissions(perms: string[] | Set<string>) {
      this.permissions = Array.from(perms);
    },

    reset() {
      this.menuTree = [];
      this.dynamicRoutes = [];
      this.generated = false;
    }
  }
});

export function usePermissionStoreHook() {
  return usePermissionStore(store);
}
