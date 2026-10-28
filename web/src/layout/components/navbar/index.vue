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
const displayRouteTitle = (name: unknown, title: unknown) => name === "AiChat" ? "智能问答" : String(title || "工作台");
const pageTitle = computed(() => displayRouteTitle(route.name, route.meta?.title));
const breadcrumbs = computed(() =>
  route.matched
    .filter(item => item.meta?.title && !item.meta?.hidden)
    .map(item => ({ title: displayRouteTitle(item.name, item.meta.title), path: item.path }))
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
    router.replace("/");
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
        <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
      </button>
      <div class="page-identity">
        <span class="page-eyebrow">DOCBASE /</span>
        <strong>{{ pageTitle }}</strong>
        <el-breadcrumb v-if="breadcrumbs.length > 1" separator="/">
          <el-breadcrumb-item
            v-for="(item, index) in breadcrumbs"
            :key="`${item.path}-${index}`"
          >{{ item.title }}</el-breadcrumb-item>
        </el-breadcrumb>
      </div>
    </div>

    <div class="navbar-right">
      <span class="system-status"><i /> 企业工作区</span>
      <span class="navbar-divider" />
      <el-dropdown trigger="click" @command="handleCommand">
        <button class="user-dropdown" type="button">
          <el-avatar :size="34" :src="userAvatarUrl" class="user-avatar">
            {{ displayName.charAt(0).toUpperCase() }}
          </el-avatar>
          <span class="user-copy">
            <strong>{{ displayName }}</strong>
            <small>个人账号</small>
          </span>
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
  padding: 0 26px 0 18px;
  flex: 0 0 var(--docbase-header-height);
  background: var(--docbase-bg);
  border-bottom: 1px solid var(--docbase-line);
  backdrop-filter: blur(18px);
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
  width: 38px;
  height: 38px;
  margin-right: 16px;
  padding: 0;
  color: var(--docbase-muted);
  font-size: 18px;
  background: var(--docbase-surface-soft);
  border: 1px solid var(--docbase-line-soft);
  border-radius: 10px;
  cursor: pointer;
}

.collapse-btn:hover,
.collapse-btn:focus-visible {
  color: var(--docbase-accent);
  background: var(--docbase-accent-soft);
  outline: none;
}

.page-identity {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.page-eyebrow {
  color: var(--docbase-amber-strong);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1.2px;
}

.page-identity strong {
  color: var(--docbase-ink);
  font-size: 14px;
  font-weight: 650;
}

.page-identity :deep(.el-breadcrumb) {
  margin-left: 5px;
}

.page-identity :deep(.el-breadcrumb__inner) {
  color: var(--docbase-subtle);
  font-size: 11px;
  font-weight: 400;
}

.system-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--docbase-muted);
  font-size: 11px;
}

.system-status i {
  width: 6px;
  height: 6px;
  background: var(--docbase-success);
  border-radius: 50%;
  animation: status-pulse 2.6s ease-out infinite;
}

@keyframes status-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(51, 112, 78, 0.32);
  }
  70% {
    box-shadow: 0 0 0 6px rgba(51, 112, 78, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(51, 112, 78, 0);
  }
}

.page-identity strong {
  animation: docbase-fade 0.4s ease both;
}

.navbar-divider {
  width: 1px;
  height: 24px;
  margin: 0 18px;
  background: var(--docbase-line);
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 150px;
  padding: 5px 7px;
  color: var(--docbase-ink);
  font: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 11px;
  cursor: pointer;
}

.user-dropdown:hover,
.user-dropdown:focus-visible {
  background: var(--docbase-surface-soft);
  outline: none;
}

.user-avatar {
  border: 2px solid var(--docbase-surface);
  box-shadow: 0 0 0 1px var(--docbase-line);
}

.user-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-copy strong {
  max-width: 120px;
  overflow: hidden;
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-copy small {
  margin-top: 1px;
  color: var(--docbase-subtle);
  font-size: 10px;
}

.dropdown-arrow {
  margin-left: auto;
  color: var(--docbase-subtle);
  font-size: 11px;
}

@media (max-width: 767px) {
  .navbar {
    padding: 0 10px 0 12px;
  }

  .system-status,
  .navbar-divider,
  .user-copy,
  .dropdown-arrow,
  .page-eyebrow,
  .page-identity :deep(.el-breadcrumb) {
    display: none;
  }

  .collapse-btn {
    margin-right: 11px;
  }

  .user-dropdown {
    min-width: auto;
    padding: 4px;
  }
}
</style>
