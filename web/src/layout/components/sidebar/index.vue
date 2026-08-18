<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { Expand, Fold } from "@element-plus/icons-vue";
import { useAppStoreHook } from "@/store/modules/app";
import { usePermissionStoreHook } from "@/store/modules/permission";
import SidebarItem from "./sidebarItem.vue";
import Logo from "./logo.vue";

const route = useRoute();
const router = useRouter();
const app = useAppStoreHook();
const permission = usePermissionStoreHook();
const { sidebarCollapsed } = storeToRefs(app);

const activeMenu = computed(() => route.path);
const menus = computed(() => permission.menus);

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
        background-color="#001529"
        text-color="#bfcbd9"
        active-text-color="#ffffff"
        unique-opened
        @select="handleSelect"
      >
        <SidebarItem
          v-for="item in menus"
          :key="item.path"
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
      <span v-show="!sidebarCollapsed">收起菜单</span>
    </button>
  </div>
</template>

<style lang="scss" scoped>
.sidebar {
  height: 100vh;
  display: flex;
  flex-direction: column;
  color: rgba(254, 254, 254, 0.68);
  background: #001529;
  box-shadow: 0 0 1px rgba(0, 0, 0, 0.65);
}

:deep(.el-menu) {
  border-right: none;
}

:deep(.el-scrollbar) {
  flex: 1;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  height: 50px;
  line-height: 50px;
  color: rgba(254, 254, 254, 0.68);
  background: transparent !important;
  transition:
    color 0.2s ease,
    background-color 0.2s ease;
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover),
:deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff !important;
}

:deep(.el-menu-item.is-active) {
  width: calc(100% - 16px);
  height: 42px;
  margin: 4px 8px;
  color: #fff !important;
  line-height: 42px;
  background: #409eff !important;
  border-radius: 3px;
}

:deep(.el-menu--collapse .el-menu-item),
:deep(.el-menu--collapse .el-sub-menu__title) {
  justify-content: center;
  padding: 0 !important;
}

:deep(.el-menu--collapse .el-menu-item.is-active) {
  width: 46px;
  margin: 4px;
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
  height: 44px;
  flex: 0 0 44px;
  padding: 0;
  color: rgba(254, 254, 254, 0.68);
  font: inherit;
  font-size: 13px;
  background: #001529;
  border: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  cursor: pointer;
  transition:
    color 0.2s ease,
    background-color 0.2s ease;
}

.sidebar-collapse:hover,
.sidebar-collapse:focus-visible {
  color: #fff;
  background: #0b2945;
  outline: none;
}
</style>
