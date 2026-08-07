/**
 * 统一消息提示封装。
 * 约束：永远不在提示中拼接 Token 原文，避免泄露。
 */
import { ElMessage, ElMessageBox } from "element-plus";
import type { ElMessageBoxOptions } from "element-plus";

const message = {
  success(msg: string) {
    ElMessage.success(sanitize(msg));
  },
  warning(msg: string) {
    ElMessage.warning(sanitize(msg));
  },
  info(msg: string) {
    ElMessage.info(sanitize(msg));
  },
  error(msg: string) {
    ElMessage.error(sanitize(msg));
  },
  confirm(msg: string, title = "系统提示", options?: ElMessageBoxOptions) {
    return ElMessageBox.confirm(sanitize(msg), title, {
      confirmButtonText: "确定",
      cancelButtonText: "取消",
      type: "warning",
      ...options
    });
  }
};

/** 防止把 token/jwt 原文打到界面上 */
function sanitize(msg: unknown): string {
  if (msg == null) return "";
  const s = String(msg);
  // 简单规则：如果出现 eyJ 开头的 JWT 段，整体替换
  return s.replace(/eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, "[token]");
}

export { message };
