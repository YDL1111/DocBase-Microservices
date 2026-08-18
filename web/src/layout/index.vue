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
  background: #f0f2f5;
}

.sidebar-container {
  position: relative;
  z-index: 1001;
  width: 210px;
  transition:
    width 0.2s ease,
    transform 0.25s ease;
  background: #001529;
  flex-shrink: 0;
}

.sidebar-collapsed .sidebar-container {
  width: 54px;
}

.main-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100vh;
  background: #f0f2f5;
}

.app-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  padding: 0;
  background: rgba(0, 0, 0, 0.3);
  border: 0;
}

@media (max-width: 767px) {
  .mobile .sidebar-container {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: 210px;
    transform: translateX(0);
  }

  .mobile.sidebar-collapsed .sidebar-container {
    width: 210px;
    pointer-events: none;
    transform: translateX(-100%);
  }

  .mobile .main-container {
    width: 100%;
  }
}
</style>
