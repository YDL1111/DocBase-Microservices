<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
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
</script>

<template>
  <div class="sidebar">
    <Logo :collapse="sidebarCollapsed" />
    <el-scrollbar wrap-class="scrollbar-wrapper">
      <el-menu
        :default-active="activeMenu"
        :collapse="sidebarCollapsed"
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
  </div>
</template>

<style lang="scss" scoped>
.sidebar {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

:deep(.el-menu) {
  border-right: none;
}
</style>
