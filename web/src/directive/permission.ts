/**
 * 按钮级权限指令 v-auth。
 * 用法：
 *   <el-button v-auth="'system:user:create'">新增</el-button>
 *   <el-button v-auth="['system:user:edit','system:user:del']">编辑</el-button>
 *
 * 校验来源：userStore.permissions（来自 /api/auth/permissions）。
 * 管理员（admin=true）默认拥有全部权限。
 */
import type { App, Directive } from "vue";
import { watchEffect, type WatchStopHandle } from "vue";
import { useUserStoreHook } from "@/store/modules/user";

function checkAuth(el: HTMLElement, binding: { value: string | string[] }): void {
  const { value } = binding;
  if (!value) return;

  const user = useUserStoreHook();
  const has = user.hasPermission(value as string | string[]);

  // Keep the node so permissions gained after token refresh can restore it.
  el.style.display = has ? "" : "none";
  el.setAttribute("aria-hidden", has ? "false" : "true");
}

const authDirective: Directive = {
  mounted(el, binding) {
    permissionWatches.set(el, watchEffect(() => checkAuth(el, binding)));
  },
  updated(el, binding) {
    permissionWatches.get(el)?.();
    permissionWatches.set(el, watchEffect(() => checkAuth(el, binding)));
  },
  unmounted(el) {
    permissionWatches.get(el)?.();
    permissionWatches.delete(el);
  }
};

const permissionWatches = new WeakMap<HTMLElement, WatchStopHandle>();

export function setupPermissionDirective(app: App): void {
  app.directive("auth", authDirective);
}

export default authDirective;
