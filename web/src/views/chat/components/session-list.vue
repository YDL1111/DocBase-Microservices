<script setup lang="ts">
import { Delete, Plus, Refresh } from "@element-plus/icons-vue";
import type { ChatSession } from "@/api/types";

defineProps<{
  sessions: ChatSession[];
  selectedSessionId: number | null;
  loading: boolean;
  deletingSessionId: number | null;
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
    <div class="session-list__toolbar">
      <strong>会话</strong>
      <div>
        <el-button v-auth="'ai:chat:list'" :icon="Refresh" circle aria-label="刷新会话" @click="emit('refresh')" />
        <el-button v-auth="'ai:chat:list'" type="primary" :icon="Plus" @click="emit('create')">新建</el-button>
      </div>
    </div>
    <el-skeleton v-if="loading && sessions.length === 0" :rows="5" animated />
    <el-empty v-else-if="sessions.length === 0" description="暂无会话" :image-size="90" />
    <ul v-else class="session-list__items">
      <li v-for="session in sessions" :key="session.id" :class="{ active: session.id === selectedSessionId }">
        <button class="session-list__select" type="button" @click="emit('select', session.id)">
          <span class="session-list__title">{{ displayTitle(session) }}</span>
          <small>知识库：{{ session.knowledgeBaseId ?? '未绑定' }} · {{ session.updatedAt }}</small>
        </button>
        <el-button v-auth="'ai:chat:list'" link type="danger" :icon="Delete" :loading="deletingSessionId === session.id" aria-label="删除会话" @click.stop="emit('delete', session)" />
      </li>
    </ul>
    <el-pagination v-if="total > 0" small background layout="prev, pager, next" :total="total" :current-page="current" :page-size="size" @current-change="emit('pageChange', $event)" />
  </aside>
</template>

<style scoped lang="scss">
.session-list { display: flex; flex-direction: column; gap: 12px; min-width: 270px; padding: 16px; border-right: 1px solid var(--el-border-color-light); }
.session-list__toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.session-list__items { display: flex; flex: 1; flex-direction: column; gap: 6px; min-height: 0; margin: 0; padding: 0; overflow: auto; list-style: none; }
.session-list__items li { display: flex; align-items: center; border: 1px solid transparent; border-radius: 6px; }
.session-list__items li.active { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); }
.session-list__select { flex: 1; min-width: 0; padding: 10px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.session-list__title, small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
small { margin-top: 4px; color: var(--el-text-color-secondary); }
</style>
