/**
 * 应用布局/设备状态 store。
 */
import { defineStore } from "pinia";
import { store } from "@/store";

export type LayoutMode = "vertical" | "horizontal" | "mix";
export type Device = "desktop" | "mobile";

export interface AppState {
  device: Device;
  sidebarCollapsed: boolean;
  layoutMode: LayoutMode;
}

function detectDevice(): Device {
  return window.innerWidth < 768 ? "mobile" : "desktop";
}

export const useAppStore = defineStore({
  id: "docbase-app",
  state: (): AppState => ({
    device: detectDevice(),
    sidebarCollapsed: false,
    layoutMode: "vertical"
  }),
  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed;
    },
    setSidebarCollapsed(collapsed: boolean) {
      this.sidebarCollapsed = collapsed;
    },
    setDevice(device: Device) {
      this.device = device;
    },
    setLayoutMode(mode: LayoutMode) {
      this.layoutMode = mode;
    }
  }
});

export function useAppStoreHook() {
  return useAppStore(store);
}
