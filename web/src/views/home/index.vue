<script setup lang="ts">
import { computed, type Component } from "vue";
import {
  Search,
  OfficeBuilding,
  ArrowRight,
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
  description: string;
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
  { title: "知识检索", meta: "KNOWLEDGE SEARCH", description: "语义检索 · 文档原文片段", path: "/search", icon: Search, accent: "#175e4c", routerName: "KnowledgeSearchPreview", area: "business" },
  { title: "文档管理", meta: "KNOWLEDGE", description: "组织文档、目录与协作成员", path: "/knowledge", icon: Collection, accent: "#175e4c", routerName: "KnowledgeList", area: "business" },
  { title: "入库任务", meta: "PIPELINE", description: "追踪解析、索引与处理结果", path: "/ingest/tasks", icon: UploadFilled, accent: "#6b6f4a", routerName: "IngestTask", area: "business" },
  { title: "智能问答", meta: "ASSISTANT", description: "从企业知识中获得可追溯回答", path: "/ai/chat", icon: ChatDotRound, accent: "#175e4c", routerName: "AiChat", area: "business" },
  { title: "组织管理", meta: "ORGANIZATION", description: "维护组织架构与部门层级", path: "/system/organizations", icon: OfficeBuilding, accent: "#167553", routerName: "SystemOrganization", area: "governance" },
  { title: "用户管理", meta: "IDENTITY", description: "维护账号、组织与使用状态", path: "/system/users", icon: User, accent: "#4f5a5e", routerName: "SystemUser", area: "governance" },
  { title: "角色管理", meta: "ACCESS", description: "配置角色与权限边界", path: "/system/roles", icon: Avatar, accent: "#175e4c", routerName: "SystemRole", area: "governance" },
  { title: "菜单管理", meta: "RESOURCE", description: "管理导航与功能资源", path: "/system/menus", icon: MenuIcon, accent: "#175e4c", routerName: "SystemMenu", area: "governance" }
];

function collectRouterNames(nodes: MenuNode[], names = new Map<string, string>()) {
  for (const node of nodes) {
    if (node.isButton !== 1 && node.routerName) names.set(node.routerName, node.path);
    if (node.children?.length) collectRouterNames(node.children, names);
  }
  return names;
}

const visibleEntries = computed(() => {
  const routerNames = collectRouterNames(permission.menus);
  routerNames.set("KnowledgeSearchPreview", "/search");
  return entries.filter(entry => routerNames.has(entry.routerName))
    .map(entry => ({ ...entry, path: routerNames.get(entry.routerName)! }));
});
const businessEntries = computed(() => visibleEntries.value.filter(entry => entry.area === "business"));
const governanceEntries = computed(() => visibleEntries.value.filter(entry => entry.area === "governance"));
const primaryEntry = computed(() => businessEntries.value[0] ?? visibleEntries.value[0]);
</script>

<template>
  <main class="workspace-page">
    <header class="workspace-masthead">
      <div class="masthead-copy">
        <p class="workspace-date">{{ today }} <span>/</span> DOCBASE WORKSPACE</p>
        <h1>欢迎回来，{{ displayName }}<span>。</span></h1>
        <p class="masthead-description">让分散的文档成为可检索、可协作、可回答的组织知识。</p>
      </div>
      <div class="masthead-side">
        <div class="workspace-summary" aria-label="当前可用模块数量">
          <strong>{{ visibleEntries.length }}</strong>
          <span>可用模块</span>
        </div>
        <router-link v-if="primaryEntry" :to="primaryEntry.path" class="hero-action">
          进入工作区 <el-icon><ArrowRight /></el-icon>
        </router-link>
      </div>
    </header>

    <section v-if="businessEntries.length" class="workspace-section">
      <div class="section-heading">
        <div><span class="section-index">01</span><h2>文档与知识</h2></div>
        <span>{{ businessEntries.length }} 个核心模块</span>
      </div>
      <div class="entry-index">
        <router-link
          v-for="(entry, index) in businessEntries"
          :key="entry.path"
          :to="entry.path"
          class="entry-card"
          :style="{ '--entry-accent': entry.accent }"
        >
          <span class="entry-number">0{{ index + 1 }}</span>
          <div class="entry-icon"><el-icon><component :is="entry.icon" /></el-icon></div>
          <div class="entry-copy">
            <span>{{ entry.meta }}</span>
            <h3>{{ entry.title }}</h3>
            <p>{{ entry.description }}</p>
          </div>
          <div class="entry-link">立即进入 <el-icon class="entry-arrow"><Right /></el-icon></div>
        </router-link>
      </div>
    </section>

    <section v-if="governanceEntries.length" class="workspace-section governance-section">
      <div class="section-heading">
        <div><span class="section-index">02</span><h2>权限管理</h2></div>
        <span>{{ governanceEntries.length }} 个管理模块</span>
      </div>
      <div class="entry-grid governance-grid">
        <router-link
          v-for="entry in governanceEntries"
          :key="entry.path"
          :to="entry.path"
          class="entry-card"
          :style="{ '--entry-accent': entry.accent }"
        >
          <div class="entry-card__top">
            <div class="entry-icon"><el-icon><component :is="entry.icon" /></el-icon></div>
          </div>
          <div class="entry-copy"><span>{{ entry.meta }}</span><h3>{{ entry.title }}</h3><p>{{ entry.description }}</p></div>
          <div class="entry-link">管理配置 <el-icon class="entry-arrow"><Right /></el-icon></div>
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

  width: min(1380px, 100%);
  min-height: 100%;
  margin: 0 auto;
}

/* 与首页一致的浅绿欢迎区。 */
.workspace-masthead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  padding: 28px;
  background: var(--docbase-surface-soft);
  border-radius: 14px;
  border-bottom: 1px solid var(--docbase-line);
}

.workspace-date {
  margin: 0 0 16px;
  color: var(--docbase-muted);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 1.4px;
}

.workspace-date span {
  margin: 0 8px;
  color: var(--docbase-amber);
}

.workspace-masthead h1 {
  margin: 0;
  color: var(--docbase-ink);
  font-size: clamp(28px, 2.8vw, 38px);
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -1.2px;
}

.workspace-masthead h1 span {
  color: var(--docbase-amber);
}

.masthead-description {
  margin: 12px 0 0;
  color: var(--docbase-muted);
  font-size: 13px;
  line-height: 1.7;
}

.masthead-side {
  display: flex;
  align-items: center;
  gap: 26px;
  flex-shrink: 0;
}

.workspace-summary {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-right: 26px;
  color: var(--docbase-subtle);
  border-right: 1px solid var(--docbase-line);
}

.workspace-summary strong {
  color: var(--docbase-ink);
  font-family: var(--docbase-font-mono);
  font-size: 30px;
  font-weight: 600;
}

.workspace-summary span {
  font-size: 13px;
}

.hero-action {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 11px 18px;
  color: #fbf9f4;
  font-size: 14px;
  font-weight: 650;
  background: var(--docbase-accent);
  border-radius: 8px;
  transition: gap .2s ease, background-color .2s ease;
}

.hero-action:hover {
  gap: 18px;
  background: var(--docbase-accent-hover);
}

.workspace-section { margin-top: 30px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; padding: 0 4px 12px; color: var(--docbase-subtle); font-size: 13px; border-bottom: 1px solid var(--docbase-line-soft); }
.section-heading > div { display: flex; align-items: center; gap: 11px; }
.section-index { color: var(--docbase-amber-strong); font-family: var(--docbase-font-mono); font-size: 13px; font-weight: 700; }
.section-heading h2 { margin: 0; color: var(--docbase-ink); font-size: 16px; font-weight: 700; letter-spacing: -.3px; }

/* 业务入口：索引式列表行，高信息密度 */
.entry-index { display: flex; flex-direction: column; }
.entry-index .entry-card {
  position: relative;
  display: grid;
  grid-template-columns: 48px 46px minmax(0, 1fr) auto;
  align-items: center;
  gap: 20px;
  padding: 20px 16px;
  color: inherit;
  border-bottom: 1px solid var(--docbase-line-soft);
  transition: background-color .18s ease;
}
.entry-index .entry-card:first-of-type { border-top: 1px solid var(--docbase-line-soft); }
.entry-index .entry-card:hover,
.entry-index .entry-card:focus-visible { background: var(--docbase-surface); outline: none; }
.entry-index .entry-card:hover .entry-icon { color: #fbf9f4; background: var(--entry-accent); }
.entry-index .entry-card:hover .entry-arrow { transform: translateX(4px); }
.entry-index .entry-number { color: var(--docbase-subtle); font-family: var(--docbase-font-mono); font-size: 13px; }
.entry-index .entry-icon { width: 42px; height: 42px; display: inline-flex; align-items: center; justify-content: center; color: var(--entry-accent); font-size: 19px; background: var(--docbase-surface-soft); border: 1px solid var(--docbase-line-soft); border-radius: 9px; transition: color .18s ease, background-color .18s ease; }
.entry-index .entry-copy > span { color: var(--entry-accent); font-size: 11px; font-weight: 700; letter-spacing: 1.6px; }
.entry-index .entry-copy h3 { margin: 3px 0 0; color: var(--docbase-ink); font-size: 17px; font-weight: 700; letter-spacing: -.3px; }
.entry-index .entry-copy p { margin: 5px 0 0; color: var(--docbase-muted); font-size: 14px; }
.entry-index .entry-link { display: flex; align-items: center; gap: 7px; color: var(--docbase-muted); font-size: 13px; white-space: nowrap; }
.entry-index .entry-arrow { color: var(--entry-accent); transition: transform .18s ease; }

/* 治理入口：紧凑网格 */
.entry-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.governance-grid .entry-card { position: relative; min-height: 168px; display: flex; flex-direction: column; padding: 20px 22px; color: inherit; background: var(--docbase-surface); border: 1px solid var(--docbase-line-soft); border-radius: var(--docbase-radius-lg); box-shadow: var(--docbase-shadow-sm); transition: border-color .18s ease, transform .18s ease; }
.governance-grid .entry-card::before { position: absolute; top: 0; left: 22px; width: 30px; height: 2px; content: ""; background: var(--entry-accent); }
.governance-grid .entry-card:hover, .governance-grid .entry-card:focus-visible { border-color: var(--docbase-line); outline: none; transform: translateY(-2px); }
.governance-grid .entry-icon { width: 40px; height: 40px; display: inline-flex; align-items: center; justify-content: center; color: var(--entry-accent); font-size: 18px; background: var(--docbase-surface-soft); border-radius: 9px; }
.governance-grid .entry-copy { margin-top: 15px; }
.governance-grid .entry-copy > span { color: var(--entry-accent); font-size: 11px; font-weight: 700; letter-spacing: 1.4px; }
.governance-grid .entry-copy h3 { margin: 4px 0 0; color: var(--docbase-ink); font-size: 16px; font-weight: 700; }
.governance-grid .entry-copy p { margin: 6px 0 0; color: var(--docbase-muted); font-size: 14px; }
.governance-grid .entry-link { display: flex; align-items: center; gap: 6px; margin-top: auto; padding-top: 14px; color: var(--docbase-muted); font-size: 13px; }
.governance-grid .entry-arrow { color: var(--entry-accent); transition: transform .18s ease; }
.governance-grid .entry-card:hover .entry-arrow { transform: translateX(3px); }
.workspace-empty { margin-top: 26px; background: var(--docbase-surface); border: 1px solid var(--docbase-line-soft); border-radius: var(--docbase-radius-lg); }

@media (max-width: 1120px) {
  .entry-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 700px) {
  .workspace-masthead { align-items: flex-start; flex-direction: column; gap: 20px; padding: 10px 2px 24px; }
  .workspace-masthead h1 { font-size: 27px; }
  .masthead-side { width: 100%; justify-content: space-between; }
  .workspace-summary { padding-right: 0; border-right: 0; }
  .entry-index .entry-card { grid-template-columns: 42px minmax(0, 1fr); gap: 8px 14px; padding: 16px 10px; }
  .entry-index .entry-number { display: none; }
  .entry-index .entry-link { grid-column: 2; padding-top: 2px; }
  .entry-grid { grid-template-columns: 1fr; }
}

/* 入场编排：刊头 → 侧栏数据 → 索引行 → 治理卡片 依次上浮（backwards 避免锁定 hover transform） */
.masthead-copy > * { animation: docbase-rise .55s cubic-bezier(.22, .61, .36, 1) backwards; }
.masthead-copy > *:nth-child(2) { animation-delay: .08s; }
.masthead-copy > *:nth-child(3) { animation-delay: .16s; }
.masthead-side { animation: docbase-rise .55s .14s cubic-bezier(.22, .61, .36, 1) backwards; }
.section-heading { animation: docbase-rise .5s .2s cubic-bezier(.22, .61, .36, 1) backwards; }
.entry-index .entry-card { animation: docbase-rise .5s cubic-bezier(.22, .61, .36, 1) backwards; }
.entry-index .entry-card:nth-of-type(1) { animation-delay: .26s; }
.entry-index .entry-card:nth-of-type(2) { animation-delay: .33s; }
.entry-index .entry-card:nth-of-type(3) { animation-delay: .4s; }
.entry-index .entry-card:nth-of-type(4) { animation-delay: .47s; }
.entry-index .entry-card:nth-of-type(5) { animation-delay: .54s; }
.entry-index .entry-card:nth-of-type(6) { animation-delay: .61s; }
.governance-grid .entry-card { animation: docbase-rise .5s cubic-bezier(.22, .61, .36, 1) backwards; }
.governance-grid .entry-card:nth-of-type(1) { animation-delay: .3s; }
.governance-grid .entry-card:nth-of-type(2) { animation-delay: .37s; }
.governance-grid .entry-card:nth-of-type(3) { animation-delay: .44s; }
.governance-grid .entry-card:nth-of-type(4) { animation-delay: .51s; }
.governance-grid .entry-card:nth-of-type(5) { animation-delay: .58s; }
.governance-grid .entry-card:nth-of-type(6) { animation-delay: .65s; }

@media (prefers-reduced-motion: reduce) {
  .entry-card, .hero-action, .entry-index .entry-icon { transition: none; }
  .masthead-copy > *, .masthead-side, .section-heading, .entry-index .entry-card, .governance-grid .entry-card { opacity: 1; animation: none; }
}
</style>
