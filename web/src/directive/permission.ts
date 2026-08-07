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
import { useUserStoreHook } from "@/store/modules/user";

function checkAuth(el: HTMLElement, binding: { value: string | string[] }): void {
  const { value } = binding;
  if (!value) return;

  const user = useUserStoreHook();
  const has = user.hasPermission(value as string | string[]);

  if (!has && el.parentNode) {
    // 无权限：移除元素（也可改用 el.style.display = 'none'）
    el.parentNode.removeChild(el);
  }
}

const authDirective: Directive = {
  mounted(el, binding) {
    checkAuth(el, binding);
  },
  updated(el, binding) {
    checkAuth(el, binding);
  }
};

export function setupPermissionDirective(app: App): void {
  app.directive("auth", authDirective);
}

export default authDirective;
