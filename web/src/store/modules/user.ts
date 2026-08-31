/**
 * 用户认证态 store。
 * 持有当前用户信息、权限码集合。
 */
import { defineStore } from "pinia";
import { store } from "@/store";
import {
  getUserInfo,
  setUserInfo as persistUserInfo,
  removeToken
} from "@/utils/auth";
import type { UserInfo } from "@/api/types";

export interface UserState {
  userId: number | null;
  username: string;
  nickname: string;
  email: string;
  phoneNumber: string;
  admin: boolean;
  organizationId: number | null;
  permissions: string[];
}

export const useUserStore = defineStore({
  id: "docbase-user",
  state: (): UserState => {
    const info = getUserInfo();
    return {
      userId: info?.userId ?? null,
      username: info?.username ?? "",
      nickname: info?.nickname ?? "",
      email: info?.email ?? "",
      phoneNumber: info?.phoneNumber ?? "",
      admin: info?.admin ?? false,
      organizationId: info?.organizationId ?? null,
      permissions: []
    };
  },
  getters: {
    isLoggedIn: (state) => !!state.userId,
    displayName: (state) => state.nickname || state.username
  },
  actions: {
    setUserInfo(info: UserInfo) {
      this.userId = info.userId;
      this.username = info.username;
      this.nickname = info.nickname;
      this.email = info.email;
      this.phoneNumber = info.phoneNumber;
      this.admin = info.admin;
      this.organizationId = info.organizationId ?? null;
      persistUserInfo(info);
    },
    setPermissions(perms: string[] | Set<string>) {
      this.permissions = Array.from(perms);
    },
    hasPermission(code: string | string[]): boolean {
      if (this.admin) return true;
      const codes = Array.isArray(code) ? code : [code];
      return codes.every(c => this.permissions.includes(c));
    },
    /** 清空用户态（登出 / 被踢） */
    clearUser() {
      this.userId = null;
      this.username = "";
      this.nickname = "";
      this.email = "";
      this.phoneNumber = "";
      this.admin = false;
      this.organizationId = null;
      this.permissions = [];
      removeToken();
    }
  }
});

export function useUserStoreHook() {
  return useUserStore(store);
}
