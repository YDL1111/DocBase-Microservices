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
          <small>{{ session.knowledgeBaseId ? `知识库 ${session.knowledgeBaseId}` : '未绑定知识库' }}</small>
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
  background: #edf4fa;
  border-right: 1px solid #d6e3ed;
}

.session-list__identity {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 6px 8px;
  color: #245f8d;
}

.session-list__identity > .el-icon {
  width: 34px;
  height: 34px;
  font-size: 18px;
  background: #dcebf6;
  border-radius: 7px;
}

.session-list__identity span,
.session-list__identity strong {
  display: block;
}

.session-list__identity span {
  margin-bottom: 2px;
  color: #71899d;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.session-list__identity strong {
  color: #173956;
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

.session-list__items {
  min-height: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  margin: 0;
  padding: 0 2px;
  overflow: auto;
  list-style: none;
}

.session-list__items li {
  position: relative;
  display: flex;
  align-items: center;
  border-radius: 6px;
}

.session-list__items li:hover {
  background: rgba(255, 255, 255, 0.68);
}

.session-list__items li.active {
  background: #d8e9f6;
  box-shadow: inset 3px 0 #2b78af;
}

.session-list__select {
  min-width: 0;
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  flex: 1;
  padding: 10px 8px;
  color: #29465e;
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.session-list__chat-icon {
  grid-row: 1 / 3;
  align-self: center;
  color: #5c7c95;
  font-size: 15px;
}

.session-list__title,
small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-list__title {
  font-size: 13px;
  font-weight: 600;
}

small {
  margin-top: 3px;
  color: #8396a6;
  font-size: 10px;
}

.session-list__delete {
  margin-right: 3px;
  opacity: 0;
}

.session-list__items li:hover .session-list__delete,
.session-list__delete:focus-visible,
.session-list__delete.is-loading {
  opacity: 1;
}

.session-list :deep(.el-pagination) {
  justify-content: center;
}

@media (max-width: 760px) {
  .session-list {
    border-right: 0;
    border-bottom: 1px solid #d6e3ed;
  }
}
</style>
