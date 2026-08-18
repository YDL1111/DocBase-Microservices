<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { Expand, Fold } from "@element-plus/icons-vue";
import { useAppStoreHook } from "@/store/modules/app";
import { useUserStoreHook } from "@/store/modules/user";
import { logoutApi } from "@/api/auth";
import { message } from "@/utils/message";
import userAvatarUrl from "@/assets/user.jpg";

const router = useRouter();
const route = useRoute();
const app = useAppStoreHook();
const user = useUserStoreHook();
const { sidebarCollapsed } = storeToRefs(app);

const displayName = computed(() => user.displayName);
const breadcrumbs = computed(() => {
  const matched = route.matched
    .filter(item => item.meta?.title && !item.meta?.hidden)
    .map(item => ({
      title: String(item.meta.title),
      path: item.path
    }))
    .filter(item => item.title !== "首页");

  return [
    { title: "首页", path: "/home" },
    ...matched
  ];
});

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
      <button
        class="collapse-btn"
        type="button"
        :aria-label="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        :title="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        @click="toggleSidebar"
      >
        <el-icon>
          <Fold v-if="!sidebarCollapsed" />
          <Expand v-else />
        </el-icon>
      </button>

      <el-breadcrumb class="breadcrumb" separator="/">
        <el-breadcrumb-item
          v-for="(item, index) in breadcrumbs"
          :key="`${item.path}-${index}`"
          :to="index < breadcrumbs.length - 1 ? item.path : undefined"
        >
          {{ item.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>
    <div class="navbar-right">
      <el-dropdown trigger="click" @command="handleLogout">
        <span class="user-dropdown">
          <el-avatar :size="26" :src="userAvatarUrl" class="user-avatar">
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
  position: relative;
  z-index: 998;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 12px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  flex: 0 0 48px;
}

.navbar-left,
.navbar-right {
  display: flex;
  align-items: center;
  height: 100%;
}

.collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  padding: 0;
  color: #606266;
  font-size: 20px;
  background: transparent;
  border: 0;
  cursor: pointer;
  transition:
    color 0.2s ease,
    background-color 0.2s ease;
}

.collapse-btn:hover,
.collapse-btn:focus-visible {
  color: #409eff;
  background: #f6f6f6;
  outline: none;
}

.breadcrumb {
  margin-left: 8px;
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 48px;
  padding: 0 10px;
  color: #303133;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.user-dropdown:hover {
  background: #f6f6f6;
}

.user-name {
  font-size: 14px;
}

@media (max-width: 767px) {
  .breadcrumb,
  .user-name {
    display: none;
  }

  .navbar {
    padding-right: 4px;
  }
}
</style>
