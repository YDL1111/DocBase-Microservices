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

/**
 * 路由出口包装组件。
 * 用于菜单中的"目录"节点——它们本身无内容，但需要渲染子路由。
 * 若目录节点使用 PlaceholderView（无 <router-view>），子路由将无法显示。
 */
const RouterViewWrapper = () => import("@/views/route-view.vue");

/**
 * 路由名称 → 真实组件的映射表。
 *
 * 后端 /api/auth/menus 返回的 MenuNode.routerName 若在此表中命中，
 * 则使用对应的真实组件渲染；未命中的回退到 PlaceholderView。
 * 这样菜单未完全迁移时不会报错，只会显示"建设中"占位页。
 *
 * 注意：使用懒加载避免首屏体积膨胀。
 */
const componentRegistry: Record<string, () => Promise<any>> = {
  Knowledge: RouterViewWrapper,
  KnowledgeList: () => import("@/views/knowledge/list.vue"),
  KnowledgeDetail: () => import("@/views/knowledge/detail.vue"),
  IngestTaskDir: RouterViewWrapper,
  IngestTask: () => import("@/views/ingest/list.vue")
};

/** 根据 routerName 解析组件，未命中则回退占位页 */
function resolveComponent(routerName: string): () => Promise<any> {
  return componentRegistry[routerName] || PlaceholderView;
}

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
        component: resolveComponent(node.routerName || ""),
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
