<script setup lang="ts">
/**
 * 路由出口包装组件。
 *
 * 用于菜单中的"目录"类型节点（如"知识库"目录），
 * 这些节点本身不需要展示内容，但需要渲染子路由。
 * 若使用 PlaceholderView（无 <router-view>），子路由将无法显示。
 */
</script>

<template>
  <router-view />
</template>
