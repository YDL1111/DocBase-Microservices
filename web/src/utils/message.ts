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
  },
  /** 危险操作确认（删除/不可恢复类）：红色确认按钮，语义与视觉一致 */
  confirmDanger(msg: string, title = "删除确认", options?: ElMessageBoxOptions) {
    return ElMessageBox.confirm(sanitize(msg), title, {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      confirmButtonClass: "el-button--danger",
      type: "error",
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
