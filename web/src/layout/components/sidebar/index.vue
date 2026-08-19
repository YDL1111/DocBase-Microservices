<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { Expand, Fold } from "@element-plus/icons-vue";
import { useAppStoreHook } from "@/store/modules/app";
import { usePermissionStoreHook } from "@/store/modules/permission";
import SidebarItem from "./sidebarItem.vue";
import Logo from "./logo.vue";
import {
  buildSidebarNavigation,
  menuContainsPath
} from "./navigation";

const route = useRoute();
const router = useRouter();
const app = useAppStoreHook();
const permission = usePermissionStoreHook();
const { sidebarCollapsed } = storeToRefs(app);

const activeMenu = computed(() => route.path);
const menus = computed(() => buildSidebarNavigation(permission.menus));
const defaultOpeneds = computed(() =>
  menus.value
    .filter(
      item =>
        item.children?.length &&
        (item.routerName === "SidebarKnowledgeGroup" ||
          menuContainsPath(item, route.path))
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
  <div class="sidebar">
    <Logo :collapse="sidebarCollapsed" />
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
    <button
      class="sidebar-collapse"
      type="button"
      :title="sidebarCollapsed ? '展开菜单' : '收起菜单'"
      :aria-label="sidebarCollapsed ? '展开菜单' : '收起菜单'"
      @click="toggleSidebar"
    >
      <el-icon>
        <Expand v-if="sidebarCollapsed" />
        <Fold v-else />
      </el-icon>
      <span v-show="!sidebarCollapsed">收起导航</span>
    </button>
  </div>
</template>

<style lang="scss" scoped>
.sidebar {
  height: 100vh;
  display: flex;
  flex-direction: column;
  color: #425a70;
  background: #f7f9fc;
  border-right: 0;
}

:deep(.el-scrollbar) {
  flex: 1;
}

:deep(.el-menu) {
  padding: 12px 8px;
  background: transparent;
  border-right: 0;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  height: 44px;
  margin: 1px 4px;
  padding-right: 12px !important;
  color: #425a70;
  font-size: 14px;
  font-weight: 500;
  line-height: 44px;
  background: transparent !important;
  border-radius: 5px;
  transition:
    color 0.18s ease,
    background-color 0.18s ease;
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover) {
  color: #1f64c5 !important;
  background: #edf3f8 !important;
}

:deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #1f64c5 !important;
  font-weight: 600;
  background: transparent !important;
}

:deep(.el-menu-item.is-active) {
  position: relative;
  color: #185fbd !important;
  font-weight: 600;
  background: #e8f1ff !important;
  box-shadow: none;
}

:deep(.el-menu-item.is-active::before) {
  position: absolute;
  top: 10px;
  left: 0;
  width: 3px;
  height: 24px;
  content: "";
  background: #246bce;
  border-radius: 0 2px 2px 0;
}

:deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  height: 40px;
  margin: 1px 4px;
  padding-left: 43px !important;
  color: #566d82;
  font-size: 13px;
  font-weight: 400;
  line-height: 40px;
  background: transparent !important;
}

:deep(.el-sub-menu .el-menu-item:hover) {
  color: #1f64c5 !important;
  background: #edf3f8 !important;
}

:deep(.el-sub-menu .el-menu-item.is-active) {
  color: #185fbd !important;
  font-weight: 600;
  background: #e8f1ff !important;
}

:deep(.el-sub-menu__icon-arrow) {
  right: 14px;
  color: #8da0b1;
  font-size: 12px;
}

:deep(.el-menu--collapse .el-menu-item),
:deep(.el-menu--collapse .el-sub-menu__title) {
  justify-content: center;
  margin: 1px 0;
  padding: 0 !important;
}

:deep(.el-menu--collapse .menu-icon) {
  margin-right: 0;
}

.sidebar-collapse {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 46px;
  flex: 0 0 46px;
  padding: 0;
  color: #687d91;
  font: inherit;
  font-size: 13px;
  background: #fff;
  border: 0;
  border-top: 1px solid #e2e8ef;
  cursor: pointer;
  transition:
    color 0.18s ease,
    background-color 0.18s ease;
}

.sidebar-collapse:hover,
.sidebar-collapse:focus-visible {
  color: #1e5f91;
  background: #edf3f8;
  outline: none;
}
</style>
