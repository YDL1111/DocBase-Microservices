<script setup lang="ts">
import { computed, type Component } from "vue";
import {
  Avatar,
  ChatDotRound,
  Collection,
  Menu as MenuIcon,
  Right,
  UploadFilled,
  User
} from "@element-plus/icons-vue";
import { useUserStoreHook } from "@/store/modules/user";
import { usePermissionStoreHook } from "@/store/modules/permission";
import type { MenuNode } from "@/api/types";

type WorkspaceArea = "business" | "governance";

interface WorkspaceEntry {
  title: string;
  meta: string;
  path: string;
  icon: Component;
  accent: string;
  routerName: string;
  area: WorkspaceArea;
}

const user = useUserStoreHook();
const permission = usePermissionStoreHook();
const displayName = computed(() => user.displayName || "DocBase 用户");
const today = new Intl.DateTimeFormat("zh-CN", {
  month: "long",
  day: "numeric",
  weekday: "long"
}).format(new Date());

const entries: WorkspaceEntry[] = [
  {
    title: "知识库列表",
    meta: "文档与分类",
    path: "/knowledge",
    icon: Collection,
    accent: "#2476c9",
    routerName: "KnowledgeList",
    area: "business"
  },
  {
    title: "入库任务",
    meta: "任务编排",
    path: "/ingest/tasks",
    icon: UploadFilled,
    accent: "#0f8b78",
    routerName: "IngestTask",
    area: "business"
  },
  {
    title: "AI 对话",
    meta: "智能检索",
    path: "/ai/chat",
    icon: ChatDotRound,
    accent: "#7657b4",
    routerName: "AiChat",
    area: "business"
  },
  {
    title: "用户管理",
    meta: "账号与状态",
    path: "/system/users",
    icon: User,
    accent: "#b66718",
    routerName: "SystemUser",
    area: "governance"
  },
  {
    title: "角色管理",
    meta: "权限与归属",
    path: "/system/roles",
    icon: Avatar,
    accent: "#a54452",
    routerName: "SystemRole",
    area: "governance"
  },
  {
    title: "菜单管理",
    meta: "导航与资源",
    path: "/system/menus",
    icon: MenuIcon,
    accent: "#536f35",
    routerName: "SystemMenu",
    area: "governance"
  }
];

function collectRouterNames(nodes: MenuNode[], names = new Set<string>()) {
  for (const node of nodes) {
    if (node.isButton !== 1 && node.routerName) names.add(node.routerName);
    if (node.children?.length) collectRouterNames(node.children, names);
  }
  return names;
}

const visibleEntries = computed(() => {
  const routerNames = collectRouterNames(permission.menus);
  return entries.filter(entry => routerNames.has(entry.routerName));
});
const businessEntries = computed(() =>
  visibleEntries.value.filter(entry => entry.area === "business")
);
const governanceEntries = computed(() =>
  visibleEntries.value.filter(entry => entry.area === "governance")
);
</script>

<template>
  <main class="workspace-page">
    <header class="workspace-header">
      <div>
        <p class="workspace-date">{{ today }}</p>
        <h1>欢迎回来，{{ displayName }}</h1>
      </div>
      <div class="workspace-summary" aria-label="当前可用模块数量">
        <strong>{{ visibleEntries.length }}</strong>
        <span>可用模块</span>
      </div>
    </header>

    <section v-if="businessEntries.length" class="workspace-section">
      <div class="section-heading">
        <div>
          <span class="section-index">01</span>
          <h2>业务工作区</h2>
        </div>
        <span>{{ businessEntries.length }} 项</span>
      </div>
      <div class="entry-grid">
        <router-link
          v-for="entry in businessEntries"
          :key="entry.path"
          :to="entry.path"
          class="entry-card"
          :style="{ '--entry-accent': entry.accent }"
        >
          <div class="entry-icon">
            <el-icon><component :is="entry.icon" /></el-icon>
          </div>
          <div class="entry-copy">
            <span>{{ entry.meta }}</span>
            <h3>{{ entry.title }}</h3>
          </div>
          <el-icon class="entry-arrow"><Right /></el-icon>
        </router-link>
      </div>
    </section>

    <section v-if="governanceEntries.length" class="workspace-section">
      <div class="section-heading">
        <div>
          <span class="section-index">02</span>
          <h2>系统治理</h2>
        </div>
        <span>{{ governanceEntries.length }} 项</span>
      </div>
      <div class="entry-grid">
        <router-link
          v-for="entry in governanceEntries"
          :key="entry.path"
          :to="entry.path"
          class="entry-card"
          :style="{ '--entry-accent': entry.accent }"
        >
          <div class="entry-icon">
            <el-icon><component :is="entry.icon" /></el-icon>
          </div>
          <div class="entry-copy">
            <span>{{ entry.meta }}</span>
            <h3>{{ entry.title }}</h3>
          </div>
          <el-icon class="entry-arrow"><Right /></el-icon>
        </router-link>
      </div>
    </section>

    <section v-if="!visibleEntries.length" class="workspace-empty">
      <el-empty description="当前账号暂无可用业务入口" :image-size="72" />
    </section>
  </main>
</template>

<style scoped>
.workspace-page {
  width: min(1280px, 100%);
  min-height: 100%;
  margin: 0 auto;
}

.workspace-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  min-height: 132px;
  padding: 26px 30px;
  color: #173754;
  background: #eef5ff;
  border-left: 5px solid #246bce;
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(40, 78, 118, 0.08);
}

.workspace-date {
  margin: 0 0 10px;
  color: #66819d;
  font-size: 13px;
}

.workspace-header h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 0;
}

.workspace-summary {
  display: flex;
  align-items: baseline;
  gap: 8px;
  color: #667f99;
}

.workspace-summary strong {
  color: #246bce;
  font-family: "DIN Alternate", "Segoe UI", sans-serif;
  font-size: 34px;
  line-height: 1;
}

.workspace-summary span {
  font-size: 12px;
}

.workspace-section {
  margin-top: 26px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: #7a8999;
  font-size: 12px;
}

.section-heading > div {
  display: flex;
  align-items: center;
  gap: 10px;
}

.section-index {
  color: #0f8b78;
  font-family: Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.section-heading h2 {
  margin: 0;
  color: #213349;
  font-size: 16px;
  font-weight: 600;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.entry-card {
  position: relative;
  min-height: 108px;
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 20px;
  overflow: hidden;
  color: inherit;
  background: #fff;
  border: 1px solid #dfe6ed;
  border-radius: 8px;
  box-shadow: 0 5px 16px rgba(23, 42, 61, 0.045);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
}

.entry-card::after {
  position: absolute;
  top: 0;
  right: 0;
  width: 4px;
  height: 100%;
  content: "";
  background: var(--entry-accent);
  opacity: 0.75;
}

.entry-card:hover,
.entry-card:focus-visible {
  border-color: color-mix(in srgb, var(--entry-accent) 48%, #dfe6ed);
  box-shadow: 0 10px 24px rgba(23, 42, 61, 0.09);
  outline: none;
  transform: translateY(-2px);
}

.entry-icon {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--entry-accent);
  font-size: 21px;
  background: color-mix(in srgb, var(--entry-accent) 9%, white);
  border: 1px solid color-mix(in srgb, var(--entry-accent) 18%, white);
  border-radius: 6px;
  flex: 0 0 42px;
}

.entry-copy {
  min-width: 0;
}

.entry-copy span {
  color: #8190a0;
  font-size: 12px;
}

.entry-copy h3 {
  margin: 5px 0 0;
  color: #1b2e44;
  font-size: 15px;
  font-weight: 600;
}

.entry-arrow {
  margin-left: auto;
  color: #a4b1bd;
  flex: 0 0 auto;
}

.workspace-empty {
  margin-top: 26px;
  background: #fff;
  border: 1px solid #dfe6ed;
  border-radius: 8px;
}

@media (max-width: 980px) {
  .entry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .workspace-header {
    min-height: 118px;
    align-items: flex-start;
    padding: 23px 20px;
  }

  .workspace-header h1 {
    font-size: 24px;
  }

  .workspace-summary {
    display: none;
  }

  .entry-grid {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .entry-card {
    transition: none;
  }
}
</style>
