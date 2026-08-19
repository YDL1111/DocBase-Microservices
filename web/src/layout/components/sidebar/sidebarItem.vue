<script setup lang="ts">
import { computed } from "vue";
import type { Component } from "vue";
import {
  Avatar,
  ChatDotRound,
  Collection,
  Document,
  HomeFilled,
  Menu as MenuIcon,
  Setting,
  UploadFilled,
  User
} from "@element-plus/icons-vue";
import type { SidebarMenuNode } from "./navigation";

interface Props {
  item: SidebarMenuNode;
  basePath?: string;
}

const props = withDefaults(defineProps<Props>(), { basePath: "" });

const visibleChildren = computed(
  () => props.item.children?.filter(child => child.isButton !== 1) ?? []
);
const isLeaf = computed(() => visibleChildren.value.length === 0);

function resolvePath(routePath: string): string {
  if (routePath.startsWith("/")) return routePath;
  const base = props.basePath.endsWith("/")
    ? props.basePath
    : `${props.basePath}/`;
  return `${base}${routePath}`.replace(/\/\//g, "/");
}

function resolveIcon(menu: SidebarMenuNode): Component {
  const routeName = menu.routerName || "";
  const path = menu.path || "";
  if (routeName === "Home") return HomeFilled;
  if (routeName === "SidebarKnowledgeGroup") return Collection;
  if (routeName === "SystemManage") return Setting;
  if (routeName === "SystemUser") return User;
  if (routeName === "SystemRole") return Avatar;
  if (routeName === "SystemMenu") return MenuIcon;
  if (routeName.startsWith("Knowledge") || path.startsWith("/knowledge")) {
    return Collection;
  }
  if (routeName.startsWith("Ingest") || path.startsWith("/ingest")) {
    return UploadFilled;
  }
  if (routeName === "AiChat" || path.startsWith("/ai/chat")) {
    return ChatDotRound;
  }
  return Document;
}
</script>

<template>
  <el-menu-item v-if="isLeaf" :index="resolvePath(item.path)">
    <el-icon class="menu-icon">
      <component :is="resolveIcon(item)" />
    </el-icon>
    <span class="menu-label">{{ item.menuName }}</span>
  </el-menu-item>

  <el-sub-menu v-else :index="resolvePath(item.path || item.menuName)">
    <template #title>
      <el-icon class="menu-icon">
        <component :is="resolveIcon(item)" />
      </el-icon>
      <span class="menu-label">{{ item.menuName }}</span>
    </template>
    <SidebarItem
      v-for="child in visibleChildren"
      :key="child.menuId"
      :item="child"
      :base-path="resolvePath(item.path || item.menuName)"
    />
  </el-sub-menu>
</template>

<style scoped>
.menu-icon {
  width: 18px;
  margin-right: 10px;
  font-size: 16px;
}

.menu-label {
  letter-spacing: 0;
}
</style>
