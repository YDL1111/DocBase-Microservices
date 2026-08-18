<script setup lang="ts">
import { computed, type Component } from "vue";
import {
  ChatDotRound,
  Collection,
  Right,
  UploadFilled
} from "@element-plus/icons-vue";
import { useUserStoreHook } from "@/store/modules/user";
import { usePermissionStoreHook } from "@/store/modules/permission";
import type { MenuNode } from "@/api/types";

interface WorkspaceEntry {
  title: string;
  description: string;
  path: string;
  action: string;
  icon: Component;
  accent: string;
  routerName: string;
}

const user = useUserStoreHook();
const permission = usePermissionStoreHook();
const displayName = computed(() => user.displayName || "DocBase 用户");

const entries: WorkspaceEntry[] = [
  {
    title: "文档资产管理",
    description: "集中维护企业制度、流程、规范与项目资料，形成可持续沉淀的知识资产。",
    path: "/knowledge",
    action: "进入知识库",
    icon: Collection,
    accent: "#409eff",
    routerName: "KnowledgeList"
  },
  {
    title: "知识入库任务",
    description: "跟踪文档解析、切块、向量化与失败重试，掌握知识入库的完整进度。",
    path: "/ingest/tasks",
    action: "查看任务",
    icon: UploadFilled,
    accent: "#14b8a6",
    routerName: "IngestTask"
  },
  {
    title: "智能问答入口",
    description: "基于已授权的企业知识内容进行检索与问答，并保留连续会话记录。",
    path: "/ai/chat",
    action: "开始对话",
    icon: ChatDotRound,
    accent: "#8b5cf6",
    routerName: "AiChat"
  }
];

function collectRouterNames(nodes: MenuNode[], names = new Set<string>()) {
  for (const node of nodes) {
    if (node.isButton !== 1 && node.routerName) {
      names.add(node.routerName);
    }
    if (node.children?.length) {
      collectRouterNames(node.children, names);
    }
  }
  return names;
}

const visibleEntries = computed(() => {
  const routerNames = collectRouterNames(permission.menus);
  return entries.filter(entry => routerNames.has(entry.routerName));
});
</script>

<template>
  <div class="workspace-page">
    <section class="workspace-hero">
      <div>
        <p class="eyebrow">Enterprise Knowledge Base</p>
        <h1>企业文档知识库工作台</h1>
        <p class="summary">
          欢迎回来，{{ displayName }}。在这里统一管理文档资产、知识入库任务与智能问答。
        </p>
      </div>
      <img class="hero-logo" src="/logo.svg" alt="" aria-hidden="true" />
    </section>

    <section v-if="visibleEntries.length" class="entry-grid" aria-label="知识库功能入口">
      <router-link
        v-for="entry in visibleEntries"
        :key="entry.path"
        :to="entry.path"
        class="entry-card"
        :style="{ '--entry-accent': entry.accent }"
      >
        <div class="entry-icon">
          <el-icon><component :is="entry.icon" /></el-icon>
        </div>
        <h2>{{ entry.title }}</h2>
        <p>{{ entry.description }}</p>
        <span class="entry-action">
          {{ entry.action }}
          <el-icon><Right /></el-icon>
        </span>
      </router-link>
    </section>

    <section v-else class="workspace-empty" aria-label="暂无可用业务入口">
      <el-empty description="当前账号暂无可用业务入口" :image-size="72" />
    </section>

    <section class="workspace-note">
      <div>
        <h2>统一身份与权限边界</h2>
        <p>
          所有页面与接口均通过 Gateway 进入微服务体系，菜单、按钮和数据操作继续由 IAM 权限模型控制。
        </p>
      </div>
      <span class="note-mark">DocBase</span>
    </section>
  </div>
</template>

<style scoped>
.workspace-page {
  min-height: 100%;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgba(20, 184, 166, 0.14), transparent 29%),
    radial-gradient(circle at right center, rgba(64, 158, 255, 0.12), transparent 26%),
    linear-gradient(180deg, #f4fbfb 0%, #f7f8fc 100%);
}

.workspace-hero,
.workspace-note,
.workspace-empty,
.entry-card {
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.06);
}

.workspace-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 190px;
  padding: 30px 34px;
  overflow: hidden;
  border-radius: 20px;
}

.eyebrow {
  margin: 0 0 12px;
  color: #0f766e;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.workspace-hero h1 {
  margin: 0;
  color: #0f172a;
  font-size: clamp(26px, 3vw, 34px);
  line-height: 1.25;
}

.summary {
  max-width: 720px;
  margin: 16px 0 0;
  color: #475569;
  font-size: 15px;
  line-height: 1.7;
}

.hero-logo {
  width: 96px;
  height: 96px;
  margin: 0 20px 0 36px;
  opacity: 0.9;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin: 20px 0;
}

.entry-card {
  position: relative;
  min-height: 230px;
  padding: 24px;
  overflow: hidden;
  border-radius: 18px;
  color: inherit;
  cursor: pointer;
  text-decoration: none;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    border-color 0.2s ease;
}

.workspace-empty {
  margin: 20px 0;
  border-radius: 18px;
}

.entry-card::before {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 3px;
  content: "";
  background: var(--entry-accent);
}

.entry-card:hover,
.entry-card:focus-visible {
  border-color: color-mix(in srgb, var(--entry-accent) 34%, transparent);
  box-shadow: 0 20px 44px rgba(15, 23, 42, 0.1);
  outline: none;
  transform: translateY(-3px);
}

.entry-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  color: var(--entry-accent);
  font-size: 24px;
  background: color-mix(in srgb, var(--entry-accent) 11%, white);
  border-radius: 12px;
}

.entry-card h2 {
  margin: 20px 0 10px;
  color: #0f172a;
  font-size: 18px;
}

.entry-card p,
.workspace-note p {
  margin: 0;
  color: #475569;
  font-size: 14px;
  line-height: 1.75;
}

.entry-action {
  position: absolute;
  right: 24px;
  bottom: 22px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--entry-accent);
  font-size: 14px;
  font-weight: 600;
}

.workspace-note {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px 28px;
  border-radius: 18px;
}

.workspace-note h2 {
  margin: 0 0 8px;
  color: #0f172a;
  font-size: 18px;
}

.note-mark {
  margin-left: 28px;
  color: #cbd5e1;
  font-family: Consolas, Monaco, monospace;
  font-size: 22px;
  font-weight: 700;
}

@media (max-width: 980px) {
  .entry-grid {
    grid-template-columns: 1fr;
  }

  .entry-card {
    min-height: 210px;
  }
}

@media (max-width: 640px) {
  .workspace-page {
    padding: 14px;
  }

  .workspace-hero {
    min-height: auto;
    padding: 24px;
  }

  .hero-logo,
  .note-mark {
    display: none;
  }

  .workspace-note {
    padding: 22px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .entry-card {
    transition: none;
  }
}
</style>
