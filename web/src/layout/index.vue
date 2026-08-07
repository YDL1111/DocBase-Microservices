<script setup lang="ts">
import { computed } from "vue";
import { storeToRefs } from "pinia";
import { useAppStoreHook } from "@/store/modules/app";
import Sidebar from "./components/sidebar/index.vue";
import Navbar from "./components/navbar/index.vue";
import AppMain from "./components/appMain.vue";

const app = useAppStoreHook();
const { sidebarCollapsed } = storeToRefs(app);

const layoutClass = computed(() => [
  "app-wrapper",
  { "sidebar-collapsed": sidebarCollapsed.value }
]);
</script>

<template>
  <div :class="layoutClass">
    <Sidebar class="sidebar-container" />
    <div class="main-container">
      <Navbar />
      <AppMain />
    </div>
  </div>
</template>

<style lang="scss" scoped>
.app-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
}

.sidebar-container {
  width: 220px;
  transition: width 0.2s;
  background: #001529;
  flex-shrink: 0;
}

.sidebar-collapsed .sidebar-container {
  width: 64px;
}

.main-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #f0f2f5;
}
</style>
