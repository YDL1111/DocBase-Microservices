<script setup lang="ts">
import { computed } from "vue";
import type { MenuNode } from "@/api/types";

interface Props {
  item: MenuNode;
  basePath?: string;
}

const props = withDefaults(defineProps<Props>(), { basePath: "" });

/** 是否还有子菜单（非按钮） */
const visibleChildren = computed(
  () =>
    props.item.children?.filter(c => c.isButton !== 1) ?? []
);

const onlyOneChild = computed(() => {
  if (visibleChildren.value.length === 1) {
    return visibleChildren.value[0];
  }
  return null;
});

function resolvePath(routePath: string): string {
  if (routePath.startsWith("/")) return routePath;
  const base = props.basePath.endsWith("/")
    ? props.basePath
    : `${props.basePath}/`;
  return `${base}${routePath}`.replace(/\/\//g, "/");
}
</script>

<template>
  <!-- 只有一个子菜单：直接展示该项 -->
  <el-menu-item
    v-if="onlyOneChild && !onlyOneChild.children?.length"
    :index="resolvePath(onlyOneChild.path)"
  >
    <span>{{ onlyOneChild.menuName }}</span>
  </el-menu-item>

  <!-- 有多个子菜单：展示为 submenu -->
  <el-sub-menu v-else :index="resolvePath(item.path || item.menuName)">
    <template #title>
      <span>{{ item.menuName }}</span>
    </template>
    <SidebarItem
      v-for="child in visibleChildren"
      :key="child.path"
      :item="child"
      :base-path="resolvePath(item.path || item.menuName)"
    />
  </el-sub-menu>
</template>
