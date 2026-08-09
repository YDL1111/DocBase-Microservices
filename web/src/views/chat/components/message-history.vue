<script setup lang="ts">
import { computed } from "vue";
import { ChatMessageRole, ChatMessageStatus, chatMessageRoleLabel, chatMessageStatusLabel, type ChatMessage } from "@/api/types";

const props = defineProps<{ messages: ChatMessage[]; loading: boolean; selectedSessionId: number | null }>();

function safeSources(value: string | null | undefined): unknown[] {
  if (!value) return [];
  try { const parsed: unknown = JSON.parse(value); return Array.isArray(parsed) ? parsed : []; } catch { return []; }
}
function sourceText(source: unknown): string {
  if (source && typeof source === "object") {
    const data = source as Record<string, unknown>;
    return [data.fileName ?? data.filename ?? data.documentId ?? data.document_id, data.page].filter(value => value !== undefined && value !== null).join(" · ");
  }
  return String(source);
}
function roleClass(role: number): string { return role === ChatMessageRole.USER ? "user" : role === ChatMessageRole.ASSISTANT ? "assistant" : "system"; }
function statusType(status: number): "success" | "warning" | "danger" | "info" { return status === ChatMessageStatus.COMPLETED ? "success" : status === ChatMessageStatus.FAILED ? "danger" : status === ChatMessageStatus.CANCELLED ? "warning" : "info"; }
const hasSession = computed(() => props.selectedSessionId !== null);
</script>

<template>
  <section class="message-history" aria-live="polite">
    <el-empty v-if="!hasSession" description="选择一个会话以查看历史消息" />
    <el-skeleton v-else-if="loading" :rows="5" animated />
    <el-empty v-else-if="messages.length === 0" description="该会话暂无历史消息" />
    <div v-else class="message-history__items">
      <article v-for="message in messages" :key="message.id" :class="['message', roleClass(message.role)]">
        <header><strong>{{ chatMessageRoleLabel(message.role) }}</strong><el-tag size="small" :type="statusType(message.status)">{{ chatMessageStatusLabel(message.status) }}</el-tag><time>{{ message.createdAt }}</time></header>
        <p>{{ message.content || '（无内容）' }}</p>
        <p v-if="message.errorCode" class="message__error">消息处理未完成，请稍后刷新会话历史。</p>
        <ul v-if="safeSources(message.sourcesJson).length" class="message__sources"><li v-for="(source, index) in safeSources(message.sourcesJson)" :key="index">{{ sourceText(source) }}</li></ul>
      </article>
    </div>
    <p v-if="hasSession" class="message-history__notice">流式问答将在下一阶段接入。</p>
  </section>
</template>

<style scoped lang="scss">
.message-history { min-width: 0; padding: 24px; overflow: auto; background: var(--el-bg-color-page); }
.message-history__items { max-width: 900px; margin: 0 auto; }
.message { margin-bottom: 14px; padding: 14px; border-radius: 8px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); }
.message.user { border-left: 4px solid var(--el-color-primary); }.message.assistant { border-left: 4px solid var(--el-color-success); }.message.system { border-left: 4px solid var(--el-color-info); }
header { display: flex; align-items: center; gap: 8px; } time { margin-left: auto; color: var(--el-text-color-secondary); font-size: 12px; } p { white-space: pre-wrap; overflow-wrap: anywhere; } .message__error { color: var(--el-color-danger); }.message__sources { margin: 0; padding-left: 20px; color: var(--el-text-color-secondary); font-size: 12px; }.message-history__notice { color: var(--el-text-color-secondary); text-align: center; }
</style>
