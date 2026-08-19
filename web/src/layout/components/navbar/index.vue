<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { ArrowDown, Expand, Fold } from "@element-plus/icons-vue";
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
const pageTitle = computed(() => String(route.meta?.title || "首页"));
const breadcrumbs = computed(() =>
  route.matched
    .filter(item => item.meta?.title && !item.meta?.hidden)
    .map(item => ({ title: String(item.meta.title), path: item.path }))
    .filter((item, index, list) =>
      index === list.findIndex(candidate => candidate.title === item.title)
    )
);

function toggleSidebar() {
  app.toggleSidebar();
}

async function handleCommand(command: string) {
  if (command !== "logout") return;
  try {
    const { getRefreshToken } = await import("@/utils/auth");
    const refreshToken = getRefreshToken();
    if (refreshToken) await logoutApi({ refreshToken });
  } catch {
    // Local authentication state must still be cleared if remote logout fails.
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
  <header class="navbar">
    <div class="navbar-left">
      <button
        class="collapse-btn"
        type="button"
        :aria-label="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        :title="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        @click="toggleSidebar"
      >
        <el-icon>
          <Expand v-if="sidebarCollapsed" />
          <Fold v-else />
        </el-icon>
      </button>

      <div class="page-identity">
        <strong>{{ pageTitle }}</strong>
        <el-breadcrumb v-if="breadcrumbs.length > 1" separator="/">
          <el-breadcrumb-item
            v-for="(item, index) in breadcrumbs"
            :key="`${item.path}-${index}`"
          >
            {{ item.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>
      </div>
    </div>

    <div class="navbar-right">
      <span class="workspace-label">DocBase 工作区</span>
      <el-dropdown trigger="click" @command="handleCommand">
        <button class="user-dropdown" type="button">
          <el-avatar :size="30" :src="userAvatarUrl" class="user-avatar">
            {{ displayName.charAt(0).toUpperCase() }}
          </el-avatar>
          <span class="user-name">{{ displayName }}</span>
          <el-icon class="dropdown-arrow"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style lang="scss" scoped>
.navbar {
  position: relative;
  z-index: 998;
  height: var(--docbase-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 14px;
  background: rgba(255, 255, 255, 0.96);
  border-bottom: 1px solid var(--docbase-line);
  flex: 0 0 var(--docbase-header-height);
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
  width: 52px;
  height: 100%;
  padding: 0;
  color: #607287;
  font-size: 19px;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.collapse-btn:hover,
.collapse-btn:focus-visible {
  color: var(--docbase-accent);
  background: #edf4fa;
  outline: none;
}

.page-identity {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.page-identity strong {
  color: var(--docbase-ink);
  font-size: 16px;
  font-weight: 600;
}

.page-identity :deep(.el-breadcrumb__inner) {
  color: #8795a5;
  font-size: 12px;
  font-weight: 400;
}

.workspace-label {
  margin-right: 12px;
  color: #718296;
  font-size: 13px;
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 8px;
  color: #24364a;
  font: inherit;
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.user-dropdown:hover,
.user-dropdown:focus-visible {
  background: #f0f4f7;
  outline: none;
}

.user-name {
  max-width: 150px;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dropdown-arrow {
  color: #91a0af;
  font-size: 12px;
}

@media (max-width: 767px) {
  .workspace-label,
  .user-name,
  .dropdown-arrow,
  .page-identity :deep(.el-breadcrumb) {
    display: none;
  }

  .navbar {
    padding-right: 5px;
  }
}
</style>
