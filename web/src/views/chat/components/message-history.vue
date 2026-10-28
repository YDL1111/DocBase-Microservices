<script setup lang="ts">
import { computed, ref } from "vue";
import { ArrowDown, ChatDotRound, CopyDocument, Delete, Refresh, RefreshRight } from "@element-plus/icons-vue";
import { ChatMessageRole, ChatMessageStatus, chatMessageRoleLabel, chatMessageStatusLabel } from "@/api/types";
import type { ChatSource } from "@/api/chat-stream";
import { RecoveryStatus, recoveryStatusText, type ChatViewMessage } from "../chat-ui";
import { renderAnswerMarkdown } from "../chat-markdown";

const props = defineProps<{
  messages: ChatViewMessage[];
  loading: boolean;
  selectedSessionId: number | null;
  streaming: boolean;
  syncing: boolean;
  cancelling: boolean;
  draining: boolean;
  canAcceptInput: boolean;
  deletingMessageId: number | null;
  attempt: { status: string; generation: number } | null;
}>();

const emit = defineEmits<{
  refresh: [];
  retry: [];
  recheck: [];
  copy: [message: ChatViewMessage];
  delete: [message: ChatViewMessage];
  resend: [message: ChatViewMessage];
}>();
const expandedSourceIds = ref(new Set<number | string>());

function isSafeSource(source: unknown): source is ChatSource {
  if (!source || typeof source !== "object" || Array.isArray(source)) return false;
  const value = source as Record<string, unknown>;
  return Number.isSafeInteger(value.document_id) && (value.document_id as number) > 0
    && (value.file_name === undefined || value.file_name === null || typeof value.file_name === "string")
    && (value.page === undefined || value.page === null || (Number.isSafeInteger(value.page) && (value.page as number) > 0))
    && (value.sheet === undefined || value.sheet === null || typeof value.sheet === "string")
    && (value.slide === undefined || value.slide === null || (Number.isSafeInteger(value.slide) && (value.slide as number) > 0))
    && (value.heading_path === undefined || value.heading_path === null || typeof value.heading_path === "string");
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
  const locations = [
    source.page ? `第 ${source.page} 页` : "",
    source.sheet?.trim() ? `Sheet ${source.sheet.trim()}` : "",
    source.slide ? `Slide ${source.slide}` : "",
    source.heading_path?.trim() || ""
  ].filter(Boolean);
  return locations.length ? `${name} · ${locations.join(" · ")}` : name;
}
function toggleSources(messageId: number | string): void {
  const next = new Set(expandedSourceIds.value);
  if (next.has(messageId)) next.delete(messageId); else next.add(messageId);
  expandedSourceIds.value = next;
}
function answerDuration(item: ChatViewMessage): string | null {
  if (!item.completedAt) return null;
  const started = Date.parse(item.createdAt);
  const completed = Date.parse(item.completedAt);
  if (!Number.isFinite(started) || !Number.isFinite(completed) || completed < started) return null;
  const elapsed = (completed - started) / 1000;
  return elapsed < 10 ? `${elapsed.toFixed(2)}s` : `${elapsed.toFixed(1)}s`;
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
        <div class="message__avatar" aria-hidden="true">{{ item.role === ChatMessageRole.USER ? '你' : item.role === ChatMessageRole.ASSISTANT ? 'AI' : '!' }}</div>
        <div class="message__body">
          <header><strong>{{ chatMessageRoleLabel(item.role) }}</strong><el-tag v-if="item.status !== ChatMessageStatus.COMPLETED" size="small" :type="statusType(item.status)">{{ chatMessageStatusLabel(item.status) }}</el-tag><time>{{ item.createdAt }}</time></header>
          <div class="message__content">
            <div
              v-if="item.content && item.role === ChatMessageRole.ASSISTANT"
              class="message__markdown"
              v-html="renderAnswerMarkdown(item.content)"
            />
            <p v-else-if="item.content">{{ item.content }}</p>
            <p v-else-if="item.status === ChatMessageStatus.STREAMING" class="message__generating">正在生成回答…</p>
            <p v-else-if="item.status === ChatMessageStatus.CANCELLED" class="message__interrupted">已停止生成。</p>
            <p v-if="item.temporary && (item.status === ChatMessageStatus.STREAMING || item.status === ChatMessageStatus.FAILED)" class="message__interrupted">结果待确认，将在连接结束后核对历史。</p>
            <p v-if="item.errorCode" class="message__error">消息处理未完成，请稍后刷新会话历史。</p>
          </div>
          <div v-if="item.role === ChatMessageRole.ASSISTANT && messageSources(item).length" class="message__references">
            <button type="button" class="message__references-toggle" :aria-expanded="expandedSourceIds.has(item.id)" @click="toggleSources(item.id)">
              <el-icon :class="{ expanded: expandedSourceIds.has(item.id) }"><ArrowDown /></el-icon>
              引用 {{ messageSources(item).length }} 项材料
              <span>查看引用</span>
            </button>
            <ul v-if="expandedSourceIds.has(item.id)" class="message__sources">
              <li v-for="(source, index) in messageSources(item)" :key="`${source.document_id}-${source.page ?? 0}-${index}`">{{ sourceText(source) }}</li>
            </ul>
          </div>
          <div v-if="item.role === ChatMessageRole.USER || item.role === ChatMessageRole.ASSISTANT" class="message__actions">
            <el-button link :icon="CopyDocument" title="复制消息" aria-label="复制消息" @click="emit('copy', item)" />
            <el-button
              v-if="item.role === ChatMessageRole.USER"
              link
              :icon="RefreshRight"
              :disabled="!canAcceptInput || inputLocked || syncing"
              title="重新发送"
              aria-label="重新发送"
              @click="emit('resend', item)"
            />
            <el-button
              v-if="item.role === ChatMessageRole.ASSISTANT && typeof item.id === 'number'"
              link
              type="danger"
              :icon="Delete"
              :loading="deletingMessageId === item.id"
              :disabled="inputLocked || syncing || deletingMessageId !== null"
              title="删除回复"
              aria-label="删除回复"
              @click="emit('delete', item)"
            />
            <span v-if="item.role === ChatMessageRole.ASSISTANT && answerDuration(item)" class="message__duration">{{ answerDuration(item) }}</span>
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
  background: var(--docbase-bg);
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
  color: var(--docbase-amber-strong);
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
  color: var(--docbase-muted);
  text-align: center;
}

.message-history__welcome > .el-icon {
  width: 54px;
  height: 54px;
  margin-bottom: 17px;
  color: var(--docbase-amber);
  font-size: 25px;
  background: var(--docbase-accent-soft);
  border-radius: 12px;
}

.message-history__welcome > span {
  color: var(--docbase-subtle);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.message-history__welcome h2 {
  margin: 8px 0 9px;
  color: var(--docbase-ink);
  font-size: 22px;
  letter-spacing: -0.4px;
}

.message-history__welcome p {
  max-width: 470px;
  margin: 0;
  color: var(--docbase-muted);
  font-size: 15px;
  line-height: 1.8;
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
  color: #f4f1e9;
  font-size: 11px;
  font-weight: 700;
  background: var(--docbase-accent);
  border-radius: 8px;
}

.message.user .message__avatar {
  color: var(--docbase-accent);
  background: var(--docbase-surface-soft);
}

.message.system .message__avatar {
  background: var(--docbase-amber);
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
  color: var(--docbase-text);
  font-size: 12px;
}

time {
  color: var(--docbase-subtle);
  font-size: 12px;
}

.message__content {
  padding: 11px 14px;
  color: var(--docbase-text);
  background: var(--docbase-surface);
  border: 1px solid var(--docbase-line-soft);
  border-radius: 4px 10px 10px 10px;
}

.message__actions {
  min-height: 28px;
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: 4px;
}

.message.user .message__actions {
  justify-content: flex-end;
}

.message__duration {
  min-width: 48px;
  margin-left: 4px;
  padding: 3px 7px;
  color: var(--docbase-accent);
  font-size: 11px;
  text-align: center;
  background: var(--docbase-accent-soft);
  border-radius: 5px;
}

.message__references {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--docbase-line-soft);
}

.message__references-toggle {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 0;
  color: var(--docbase-muted);
  font: inherit;
  font-size: 12px;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.message__references-toggle span {
  margin-left: 4px;
  color: var(--docbase-accent);
  font-weight: 600;
}

.message__references-toggle .el-icon {
  transition: transform 160ms ease;
}

.message__references-toggle .el-icon.expanded {
  transform: rotate(180deg);
}

.message.user .message__content {
  color: #164b38;
  background: var(--docbase-accent-soft);
  border-color: var(--docbase-accent);
  border-radius: 10px 4px 10px 10px;
}

.message.system .message__content {
  background: var(--docbase-amber-soft);
  border-color: #eadfc2;
}

p {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 15px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.message__markdown {
  overflow-wrap: anywhere;
  font-size: 15px;
  line-height: 1.8;
}

.message__markdown :deep(p),
.message__markdown :deep(ul),
.message__markdown :deep(ol),
.message__markdown :deep(blockquote),
.message__markdown :deep(pre) {
  margin: 0 0 10px;
}

.message__markdown :deep(:last-child) {
  margin-bottom: 0;
}

.message__markdown :deep(ul),
.message__markdown :deep(ol) {
  padding-left: 22px;
}

.message__markdown :deep(li + li) {
  margin-top: 4px;
}

.message__markdown :deep(code) {
  padding: 2px 5px;
  color: var(--docbase-accent-hover);
  background: var(--docbase-accent-soft);
  border-radius: 4px;
  font-family: var(--docbase-font-mono);
  font-size: 0.92em;
}

.message__markdown :deep(pre) {
  overflow: auto;
  padding: 12px;
  background: var(--docbase-surface-soft);
  border: 1px solid var(--docbase-line-soft);
  border-radius: 6px;
}

.message__markdown :deep(pre code) {
  padding: 0;
  background: transparent;
}

.message__markdown :deep(blockquote) {
  padding-left: 12px;
  color: var(--docbase-muted);
  border-left: 3px solid var(--docbase-amber);
}

.message__markdown :deep(a) {
  color: var(--docbase-accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.message__error {
  color: var(--docbase-danger);
}

.message__generating {
  color: var(--docbase-accent);
}

.message__interrupted {
  color: var(--docbase-subtle);
}

.message__sources {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0 0;
  padding: 0;
  color: var(--docbase-text);
  font-size: 11px;
  list-style: none;
}

.message__sources li {
  padding: 4px 7px;
  background: var(--docbase-surface-soft);
  border: 1px solid var(--docbase-line-soft);
  border-radius: 5px;
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
