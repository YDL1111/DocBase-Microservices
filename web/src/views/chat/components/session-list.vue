<script setup lang="ts">
import { ChatDotRound, Delete, Plus, Refresh } from "@element-plus/icons-vue";
import type { ChatSession } from "@/api/types";

const props = defineProps<{
  sessions: ChatSession[];
  selectedSessionId: number | null;
  loading: boolean;
  deletingSessionId: number | null;
  /** Session currently locked by an in-flight background recovery; its delete button is disabled. */
  lockedSessionId: number | null;
  current: number;
  size: number;
  total: number;
}>();
const emit = defineEmits<{
  create: [];
  select: [sessionId: number];
  delete: [session: ChatSession];
  refresh: [];
  pageChange: [current: number];
  sizeChange: [size: number];
}>();

function displayTitle(session: ChatSession): string {
  return session.title.trim() || "未命名会话";
}
</script>

<template>
  <aside v-auth="'ai:chat:list'" class="session-list">
    <div class="session-list__identity">
      <el-icon><ChatDotRound /></el-icon>
      <div>
        <span>AI WORKSPACE</span>
        <strong>对话记录</strong>
      </div>
    </div>
    <div class="session-list__toolbar">
      <el-button v-auth="'ai:chat:list'" type="primary" :icon="Plus" @click="emit('create')">新对话</el-button>
      <el-button v-auth="'ai:chat:list'" :icon="Refresh" circle title="刷新会话" aria-label="刷新会话" @click="emit('refresh')" />
    </div>
    <el-skeleton v-if="loading && sessions.length === 0" :rows="5" animated />
    <el-empty v-else-if="sessions.length === 0" description="暂无会话" :image-size="90" />
    <ul v-else class="session-list__items">
      <li v-for="session in sessions" :key="session.id" :class="{ active: session.id === selectedSessionId }">
        <button class="session-list__select" type="button" @click="emit('select', session.id)">
          <el-icon class="session-list__chat-icon"><ChatDotRound /></el-icon>
          <span class="session-list__title">{{ displayTitle(session) }}</span>
          <small>{{ (session.knowledgeBaseIds?.length ?? (session.knowledgeBaseId ? 1 : 0)) > 0 ? `已绑定 ${session.knowledgeBaseIds?.length ?? 1} 个知识库` : '通用 AI 对话' }}</small>
        </button>
        <el-button class="session-list__delete" v-auth="'ai:chat:list'" link type="danger" :icon="Delete" :loading="deletingSessionId === session.id" :disabled="session.id === lockedSessionId" title="删除会话" aria-label="删除会话" @click.stop="emit('delete', session)" />
      </li>
    </ul>
    <el-pagination v-if="total > 0" small background layout="prev, pager, next" :total="total" :current-page="current" :page-size="size" @current-change="emit('pageChange', $event)" />
  </aside>
</template>

<style scoped lang="scss">
.session-list {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 12px 12px;
  background: var(--docbase-surface-soft);
  border-right: 1px solid var(--docbase-line);
}

.session-list__identity {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 6px 10px;
  color: var(--docbase-ink);
}

.session-list__identity > .el-icon {
  width: 34px;
  height: 34px;
  color: var(--docbase-amber);
  font-size: 18px;
  background: var(--docbase-accent-soft);
  border: 1px solid var(--docbase-line);
  border-radius: 8px;
}

.session-list__identity span,
.session-list__identity strong {
  display: block;
}

.session-list__identity span {
  margin-bottom: 2px;
  color: var(--docbase-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.session-list__identity strong {
  color: var(--docbase-ink);
  font-size: 15px;
}

.session-list__toolbar {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 3px 6px;
}

.session-list__toolbar :deep(.el-button:first-child) {
  flex: 1;
}

/* 浅色会话栏与主工作区共享品牌强调色 */
.session-list__toolbar :deep(.el-button--primary) {
  color: #fff;
  background: var(--docbase-accent);
  border-color: transparent;
  box-shadow: none;
}

.session-list__toolbar :deep(.el-button--primary:hover) {
  color: #fff;
  background: var(--docbase-accent-hover);
  box-shadow: none;
}

.session-list__toolbar :deep(.el-button.is-circle) {
  color: var(--docbase-text);
  background: var(--docbase-accent-soft);
  border-color: var(--docbase-line);
}

.session-list__toolbar :deep(.el-button.is-circle:hover) {
  color: var(--docbase-ink);
  background: var(--docbase-accent-soft);
}

.session-list__items {
  min-height: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0 2px;
  overflow: auto;
  list-style: none;
}

.session-list__items li {
  position: relative;
  display: flex;
  align-items: center;
  border-radius: 7px;
}

.session-list__items li:hover {
  background: var(--docbase-accent-soft);
}

.session-list__items li.active {
  background: var(--docbase-accent-soft);
  box-shadow: inset 2px 0 0 var(--docbase-amber);
}

.session-list__select {
  min-width: 0;
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  flex: 1;
  padding: 10px 9px;
  color: var(--docbase-text);
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.session-list__items li.active .session-list__select {
  color: var(--docbase-accent);
}

.session-list__chat-icon {
  grid-row: 1 / 3;
  align-self: center;
  color: var(--docbase-muted);
  font-size: 15px;
}

.session-list__items li.active .session-list__chat-icon {
  color: var(--docbase-amber);
}

.session-list__title,
small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-list__title {
  font-size: 14px;
  font-weight: 600;
}

small {
  margin-top: 3px;
  color: var(--docbase-muted);
  font-size: 12px;
}

.session-list__delete {
  margin-right: 3px;
  opacity: 0;
}

.session-list__items li:hover .session-list__delete,
.session-list__items li:focus-within .session-list__delete,
.session-list__delete:focus-visible,
.session-list__delete.is-loading {
  opacity: 1;
}

/* 触屏设备无 hover，删除按钮常显 */
@media (hover: none) {
  .session-list__delete {
    opacity: 1;
  }
}

.session-list :deep(.el-pagination) {
  justify-content: center;
  --el-pagination-hover-color: var(--docbase-amber);
  color: var(--docbase-muted);
}

.session-list :deep(.el-pagination .el-pager li) {
  color: var(--docbase-text);
}

.session-list :deep(.el-pagination .el-pager li.is-active) {
  color: var(--docbase-accent);
  background: var(--docbase-accent-soft);
}

@media (max-width: 760px) {
  .session-list {
    border-right: 0;
    border-bottom: 1px solid var(--docbase-line);
  }
}
</style>
