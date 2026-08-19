<script setup lang="ts">
import { computed } from "vue";
import { ChatDotRound, Refresh } from "@element-plus/icons-vue";
import { ChatMessageRole, ChatMessageStatus, chatMessageRoleLabel, chatMessageStatusLabel } from "@/api/types";
import type { ChatSource } from "@/api/chat-stream";
import { RecoveryStatus, recoveryStatusText, type ChatViewMessage } from "../chat-ui";

const props = defineProps<{
  messages: ChatViewMessage[];
  loading: boolean;
  selectedSessionId: number | null;
  streaming: boolean;
  syncing: boolean;
  cancelling: boolean;
  draining: boolean;
  canAcceptInput: boolean;
  attempt: { status: string; generation: number } | null;
}>();

const emit = defineEmits<{ refresh: []; retry: []; recheck: [] }>();

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

const showRetry = computed(() => props.attempt?.status === RecoveryStatus.RETRYABLE);
const showRecheck = computed(() => props.attempt?.status === RecoveryStatus.UNCERTAIN || props.attempt?.status === RecoveryStatus.RETRYABLE || props.attempt?.status === RecoveryStatus.RECHECKING);
const statusText = computed(() => props.attempt ? recoveryStatusText(props.attempt.status as any) : "");
const inputLocked = computed(() => props.streaming || props.cancelling || props.draining);
</script>

<template>
  <section class="message-history" aria-live="polite">
    <div v-if="hasSession" class="message-history__toolbar">
      <span v-if="statusText" class="message-history__status" role="status">{{ statusText }}</span>
      <span v-else class="message-history__spacer" />
      <el-button :icon="Refresh" :loading="syncing" :disabled="inputLocked || syncing" circle title="刷新消息" aria-label="刷新消息历史" @click="emit('refresh')" />
      <el-button v-if="showRecheck" :loading="attempt?.status === RecoveryStatus.RECHECKING || syncing" :disabled="inputLocked || syncing" size="small" @click="emit('recheck')">重新检查</el-button>
      <el-button v-if="showRetry" :disabled="!canAcceptInput || inputLocked" size="small" type="primary" @click="emit('retry')">重试</el-button>
    </div>
    <div v-if="!hasSession" class="message-history__welcome">
      <el-icon><ChatDotRound /></el-icon>
      <span>DOCBASE AI</span>
      <h2>让知识更容易被找到</h2>
      <p>新建或选择一个会话，AI 将基于已绑定的知识库回答问题，并保留文档来源。</p>
    </div>
    <el-skeleton v-else-if="loading" :rows="5" animated />
    <div v-else-if="visibleMessages.length === 0" class="message-history__welcome compact">
      <el-icon><ChatDotRound /></el-icon>
      <h2>开始提问</h2>
      <p>可以总结知识库内容、查找制度条款，或让 AI 解释复杂概念。</p>
    </div>
    <div v-else class="message-history__items">
      <article v-for="item in visibleMessages" :key="item.id" :class="['message', roleClass(item.role)]">
        <div class="message__avatar">{{ item.role === ChatMessageRole.USER ? '你' : item.role === ChatMessageRole.ASSISTANT ? 'AI' : '!' }}</div>
        <div class="message__body">
          <header><strong>{{ chatMessageRoleLabel(item.role) }}</strong><el-tag size="small" :type="statusType(item.status)">{{ chatMessageStatusLabel(item.status) }}</el-tag><time>{{ item.createdAt }}</time></header>
          <div class="message__content">
            <p v-if="item.content">{{ item.content }}</p>
            <p v-else-if="item.status === ChatMessageStatus.STREAMING" class="message__generating">正在生成回答…</p>
            <p v-else-if="item.status === ChatMessageStatus.CANCELLED" class="message__interrupted">已停止生成。</p>
            <p v-if="item.temporary && (item.status === ChatMessageStatus.STREAMING || item.status === ChatMessageStatus.FAILED)" class="message__interrupted">结果待确认，将在连接结束后核对历史。</p>
            <p v-if="item.errorCode" class="message__error">消息处理未完成，请稍后刷新会话历史。</p>
            <ul v-if="messageSources(item).length" class="message__sources"><li v-for="(source, index) in messageSources(item)" :key="index">{{ sourceText(source) }}</li></ul>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped lang="scss">
.message-history {
  min-width: 0;
  padding: 18px 24px 12px;
  overflow: auto;
  background: #f7fafe;
}

.message-history__toolbar {
  max-width: 920px;
  min-height: 32px;
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 auto 10px;
}

.message-history__status {
  color: #a66a16;
  font-size: 12px;
}

.message-history__spacer {
  flex: 1;
}

.message-history__welcome {
  width: min(560px, 90%);
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  margin: auto;
  padding: 40px 20px;
  color: #526f87;
  text-align: center;
}

.message-history__welcome > .el-icon {
  width: 54px;
  height: 54px;
  margin-bottom: 17px;
  color: #256fa8;
  font-size: 25px;
  background: #deedf8;
  border-radius: 8px;
}

.message-history__welcome > span {
  color: #66849d;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.message-history__welcome h2 {
  margin: 8px 0 9px;
  color: #173956;
  font-size: 22px;
  letter-spacing: 0;
}

.message-history__welcome p {
  max-width: 470px;
  margin: 0;
  color: #70879a;
  font-size: 13px;
  line-height: 1.75;
}

.message-history__welcome.compact {
  min-height: 74%;
}

.message-history__items {
  max-width: 920px;
  margin: 0 auto;
}

.message {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  margin-bottom: 18px;
}

.message.user {
  flex-direction: row-reverse;
}

.message__avatar {
  width: 31px;
  height: 31px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 31px;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  background: #2b75aa;
  border-radius: 7px;
}

.message.user .message__avatar {
  background: #627f96;
}

.message.system .message__avatar {
  background: #9b7a49;
}

.message__body {
  max-width: min(760px, calc(100% - 46px));
}

.message.user .message__body {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
}

header {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 24px;
  margin-bottom: 4px;
}

header strong {
  color: #35556e;
  font-size: 12px;
}

time {
  color: #94a4b1;
  font-size: 10px;
}

.message__content {
  padding: 11px 14px;
  color: #253e52;
  background: #fff;
  border: 1px solid #dee8ef;
  border-radius: 4px 8px 8px 8px;
}

.message.user .message__content {
  color: #fff;
  background: #2b75aa;
  border-color: #2b75aa;
  border-radius: 8px 4px 8px 8px;
}

.message.system .message__content {
  background: #fffaf1;
  border-color: #eee1cb;
}

p {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.message__error {
  color: #c64a4a;
}

.message__generating {
  color: #256fa8;
}

.message__interrupted {
  color: #778c9d;
}

.message__sources {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 10px 0 0;
  padding: 0;
  color: #52728a;
  font-size: 11px;
  list-style: none;
}

.message__sources li {
  padding: 4px 7px;
  background: #edf5fa;
  border-radius: 4px;
}

@media (max-width: 640px) {
  .message-history {
    padding: 14px 12px 8px;
  }

  .message__body {
    max-width: calc(100% - 42px);
  }
}
</style>
