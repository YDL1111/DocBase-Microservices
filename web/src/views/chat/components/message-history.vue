<script setup lang="ts">
import { computed } from "vue";
import { ChatMessageRole, ChatMessageStatus, chatMessageRoleLabel, chatMessageStatusLabel } from "@/api/types";
import type { ChatSource } from "@/api/chat-stream";
import type { ChatViewMessage } from "../chat-ui";

const props = defineProps<{ messages: ChatViewMessage[]; loading: boolean; selectedSessionId: number | null }>();

function isSafeSource(source: unknown): source is ChatSource {
  if (!source || typeof source !== "object" || Array.isArray(source)) return false;
  const value = source as Record<string, unknown>;
  return Number.isSafeInteger(value.document_id) && (value.document_id as number) > 0
    && (value.file_name === undefined || value.file_name === null || typeof value.file_name === "string")
    && (value.page === undefined || value.page === null || (Number.isSafeInteger(value.page) && (value.page as number) > 0));
}

function safeSources(value: string | null | undefined): ChatSource[] {
  if (!value) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter(isSafeSource) : [];
  } catch { return []; }
}

function messageSources(item: ChatViewMessage): ChatSource[] { return item.sources?.filter(isSafeSource) ?? safeSources(item.sourcesJson); }
function sourceText(source: ChatSource): string {
  const name = source.file_name?.trim() || "文档";
  return source.page ? `${name} · 第 ${source.page} 页` : name;
}
function roleClass(role: number): string { return role === ChatMessageRole.USER ? "user" : role === ChatMessageRole.ASSISTANT ? "assistant" : "system"; }
function statusType(status: number): "success" | "warning" | "danger" | "info" { return status === ChatMessageStatus.COMPLETED ? "success" : status === ChatMessageStatus.FAILED ? "danger" : status === ChatMessageStatus.CANCELLED ? "warning" : "info"; }
const hasSession = computed(() => props.selectedSessionId !== null);
const visibleMessages = computed(() => props.messages.filter(item => !(item.role === ChatMessageRole.ASSISTANT && item.status === ChatMessageStatus.COMPLETED && !item.content.trim() && messageSources(item).length === 0)));
</script>

<template>
  <section class="message-history" aria-live="polite">
    <el-empty v-if="!hasSession" description="选择一个会话以查看历史消息" />
    <el-skeleton v-else-if="loading" :rows="5" animated />
    <el-empty v-else-if="visibleMessages.length === 0" description="该会话暂无历史消息" />
    <div v-else class="message-history__items">
      <article v-for="item in visibleMessages" :key="item.id" :class="['message', roleClass(item.role)]">
        <header><strong>{{ chatMessageRoleLabel(item.role) }}</strong><el-tag size="small" :type="statusType(item.status)">{{ chatMessageStatusLabel(item.status) }}</el-tag><time>{{ item.createdAt }}</time></header>
        <p v-if="item.content">{{ item.content }}</p>
        <p v-else-if="item.status === ChatMessageStatus.STREAMING" class="message__generating">正在生成回答…</p>
        <p v-else-if="item.status === ChatMessageStatus.CANCELLED" class="message__interrupted">已停止生成。</p>
        <p v-if="item.errorCode" class="message__error">消息处理未完成，请稍后刷新会话历史。</p>
        <ul v-if="messageSources(item).length" class="message__sources"><li v-for="(source, index) in messageSources(item)" :key="index">{{ sourceText(source) }}</li></ul>
      </article>
    </div>
  </section>
</template>

<style scoped lang="scss">
.message-history { min-width: 0; padding: 24px; overflow: auto; background: var(--el-bg-color-page); }
.message-history__items { max-width: 900px; margin: 0 auto; }
.message { margin-bottom: 14px; padding: 14px; border-radius: 8px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); }
.message.user { border-left: 4px solid var(--el-color-primary); }.message.assistant { border-left: 4px solid var(--el-color-success); }.message.system { border-left: 4px solid var(--el-color-info); }
header { display: flex; align-items: center; gap: 8px; } time { margin-left: auto; color: var(--el-text-color-secondary); font-size: 12px; } p { white-space: pre-wrap; overflow-wrap: anywhere; }
.message__error { color: var(--el-color-danger); }.message__generating { color: var(--el-color-primary); }.message__interrupted { color: var(--el-text-color-secondary); }.message__sources { margin: 0; padding-left: 20px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
