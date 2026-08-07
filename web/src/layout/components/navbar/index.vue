<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { Expand, Fold } from "@element-plus/icons-vue";
import { useAppStoreHook } from "@/store/modules/app";
import { useUserStoreHook } from "@/store/modules/user";
import { logoutApi } from "@/api/auth";
import { message } from "@/utils/message";

const router = useRouter();
const app = useAppStoreHook();
const user = useUserStoreHook();
const { sidebarCollapsed } = storeToRefs(app);

const displayName = computed(() => user.displayName);

function toggleSidebar() {
  app.toggleSidebar();
}

async function handleLogout() {
  try {
    const { getRefreshToken } = await import("@/utils/auth");
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      await logoutApi({ refreshToken });
    }
  } catch {
    // 登出接口失败也要清理本地态
  } finally {
    user.clearUser();
    const { resetRouter } = await import("@/router");
    resetRouter();
    message.success("已退出登录");
    router.push("/login");
  }
}
</script>

<template>
  <div class="navbar">
    <div class="navbar-left">
      <el-icon class="collapse-btn" @click="toggleSidebar">
        <Fold v-if="!sidebarCollapsed" />
        <Expand v-else />
      </el-icon>
    </div>
    <div class="navbar-right">
      <el-dropdown trigger="click" @command="handleLogout">
        <span class="user-dropdown">
          <el-avatar :size="28" class="user-avatar">
            {{ displayName.charAt(0).toUpperCase() }}
          </el-avatar>
          <span class="user-name">{{ displayName }}</span>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.navbar {
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.user-name {
  font-size: 14px;
}
</style>
