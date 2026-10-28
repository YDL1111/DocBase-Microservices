<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { Expand, Fold } from "@element-plus/icons-vue";
import { useAppStoreHook } from "@/store/modules/app";
import { usePermissionStoreHook } from "@/store/modules/permission";
import SidebarItem from "./sidebarItem.vue";
import Logo from "./logo.vue";
import { buildSidebarNavigation, menuContainsPath } from "./navigation";

const route = useRoute();
const router = useRouter();
const app = useAppStoreHook();
const permission = usePermissionStoreHook();
const { sidebarCollapsed } = storeToRefs(app);

const activeMenu = computed(() => route.path);
const menus = computed(() => buildSidebarNavigation(permission.menus));
const defaultOpeneds = computed(() =>
  menus.value
    .filter(item =>
      item.children?.length &&
      (item.routerName === "SidebarKnowledgeGroup" || menuContainsPath(item, route.path))
    )
    .map(item => item.path)
);

function handleSelect(path: string) {
  router.push(path);
}

function toggleSidebar() {
  app.toggleSidebar();
}
</script>

<template>
  <div class="sidebar" :class="{ 'is-collapsed': sidebarCollapsed }">
    <Logo :collapse="sidebarCollapsed" />
    <div v-show="!sidebarCollapsed" class="sidebar-caption">工作空间</div>
    <el-scrollbar wrap-class="scrollbar-wrapper">
      <el-menu
        :default-active="activeMenu"
        :collapse="sidebarCollapsed"
        :collapse-transition="false"
        :default-openeds="defaultOpeneds"
        @select="handleSelect"
      >
        <SidebarItem
          v-for="item in menus"
          :key="item.menuId"
          :item="item"
          :base-path="item.path"
        />
      </el-menu>
    </el-scrollbar>
    <div class="sidebar-footer">
      <div v-show="!sidebarCollapsed" class="workspace-chip">
        <span class="workspace-chip__dot" />
        <span>企业知识工作区</span>

      </div>
      <button
        class="sidebar-collapse"
        type="button"
        :title="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        :aria-label="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        @click="toggleSidebar"
      >
        <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
        <span v-show="!sidebarCollapsed">收起导航</span>
      </button>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.sidebar {
  --docbase-text: #34443b;
  --docbase-muted: #59665e;
  height: 100vh;
  display: flex;
  flex-direction: column;
  color: #34443b;
  background: #fafbf9;
  border-right: 1px solid #d9dfda;
}
.sidebar-caption { padding: 18px 26px 10px; color: #67736b; font-size: 12px; font-weight: 500; border-top: 1px solid #e5e9e5; }
:deep(.el-scrollbar) { flex: 1; min-height: 0; }
:deep(.el-menu) { padding: 0 12px 18px; background: transparent; border-right: 0; }
:deep(.el-menu-item), :deep(.el-sub-menu__title) {
  height: 46px; margin: 4px 0; padding-right: 16px !important;
  color: #34443b; font-size: 14px; font-weight: 500; line-height: 46px;
  background: transparent; border-radius: 8px;
  transition: color .16s ease, background-color .16s ease;
}
:deep(.el-menu-item:hover), :deep(.el-sub-menu__title:hover) { color: #174f39; background: #edf1ed; }
:deep(.menu-icon) { color: #5c6b61; }
:deep(.el-sub-menu.is-active > .el-sub-menu__title) { color: #174f39; font-weight: 650; }
:deep(.el-menu-item.is-active) {
  color: #fff; font-weight: 650; background: #167553;
  box-shadow: 0 2px 4px rgba(22, 75, 56, .1);
}
:deep(.el-menu-item.is-active .menu-icon) { color: inherit; }
:deep(.el-sub-menu .el-menu) { padding: 2px 0 8px; margin-left: 28px; border-left: 1px solid #d5ded6; }
:deep(.el-sub-menu .el-menu-item) {
  min-width: 0; height: 40px; line-height: 40px; margin: 3px 0 3px 10px;
  padding-left: 12px !important; font-size: 13px; font-weight: 400;
}
:deep(.el-sub-menu .el-menu-item.is-active) { color: #145237; font-weight: 650; background: #e1f0e6; box-shadow: inset 3px 0 0 #167553; }
:deep(.el-sub-menu__icon-arrow) { right: 14px; color: #67736b; font-size: 12px; }
:deep(.el-menu--collapse) { padding: 8px 12px; }
:deep(.el-menu--collapse .el-menu-item), :deep(.el-menu--collapse .el-sub-menu__title) { justify-content: center; padding: 0 !important; }
:deep(.el-menu--collapse .menu-icon) { margin-right: 0; }
:deep(.el-menu-item:focus-visible), :deep(.el-sub-menu__title:focus-visible) { outline: 2px solid #167553; outline-offset: 2px; }
.sidebar-footer { padding: 12px; flex: 0 0 auto; border-top: 1px solid #e0e5e0; }
.workspace-chip { display: flex; align-items: center; gap: 10px; padding: 10px 12px 14px; color: #536259; font-size: 12px; }
.workspace-chip__dot { width: 6px; height: 6px; background: #167553; border-radius: 50%; }
.sidebar-collapse { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; height: 38px; padding: 0; color: #536259; font: inherit; font-size: 13px; background: #f0f3ef; border: 1px solid #e0e5df; border-radius: 7px; cursor: pointer; }
.sidebar-collapse:hover { color: #174f39; background: #e5ece5; }
.sidebar-collapse:focus-visible { outline: 2px solid #167553; outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) { :deep(.el-menu-item), :deep(.el-sub-menu__title) { transition: none; } }
</style>
