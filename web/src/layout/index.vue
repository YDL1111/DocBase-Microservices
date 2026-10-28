<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from "vue";
import { storeToRefs } from "pinia";
import { useAppStoreHook } from "@/store/modules/app";
import Sidebar from "./components/sidebar/index.vue";
import Navbar from "./components/navbar/index.vue";
import AppMain from "./components/appMain.vue";

const app = useAppStoreHook();
const { device, sidebarCollapsed } = storeToRefs(app);

const layoutClass = computed(() => [
  "app-wrapper",
  {
    "sidebar-collapsed": sidebarCollapsed.value,
    mobile: device.value === "mobile"
  }
]);

function syncDevice() {
  const nextDevice = window.innerWidth < 768 ? "mobile" : "desktop";
  if (app.device !== nextDevice) {
    app.setDevice(nextDevice);
    app.setSidebarCollapsed(nextDevice === "mobile");
  }
}

onMounted(() => {
  syncDevice();
  window.addEventListener("resize", syncDevice);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", syncDevice);
});
</script>

<template>
  <div :class="layoutClass">
    <button
      v-if="device === 'mobile' && !sidebarCollapsed"
      class="app-mask"
      type="button"
      aria-label="关闭侧边菜单"
      @click="app.setSidebarCollapsed(true)"
    />
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
  overflow: hidden;
  background: var(--docbase-bg);
}

.sidebar-container {
  position: relative;
  z-index: 1001;
  width: var(--docbase-sidebar-width);
  flex-shrink: 0;
  overflow: hidden;
  background: var(--docbase-surface-soft);
  box-shadow: none;
  transition: width 0.24s cubic-bezier(0.4, 0, 0.2, 1), transform 0.25s ease;
}

.sidebar-collapsed .sidebar-container {
  width: var(--docbase-sidebar-collapsed-width);
}

.main-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100vh;
  background: var(--docbase-bg);
}

.app-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  padding: 0;
  background: rgba(17, 17, 26, 0.48);
  border: 0;
  backdrop-filter: blur(3px);
}

@media (max-width: 767px) {
  .mobile .sidebar-container {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: var(--docbase-sidebar-width);
    transform: translateX(0);
  }

  .mobile.sidebar-collapsed .sidebar-container {
    width: var(--docbase-sidebar-width);
    pointer-events: none;
    transform: translateX(-100%);
  }

  .mobile .main-container {
    width: 100%;
  }
}
</style>
