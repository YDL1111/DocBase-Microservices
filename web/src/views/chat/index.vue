<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { ElMessageBox } from "element-plus";
import { createChatSession, deleteChatMessage, deleteChatSession, listChatMessages, listChatSessions, replaceChatSessionKnowledgeBases } from "@/api/chat";
import { listKnowledgeBases } from "@/api/knowledge";
import { ChatMessageRole, type ChatSession, type CreateChatSessionRequest, type KnowledgeBase } from "@/api/types";
import { message } from "@/utils/message";
import { useUserStoreHook } from "@/store/modules/user";
import { useChatStream } from "./composables/use-chat-stream";
import { toChatViewMessage, RecoveryStatus, type ChatViewMessage } from "./chat-ui";
import { answerBody } from "./chat-markdown";
import SessionList from "./components/session-list.vue";
import MessageHistory from "./components/message-history.vue";
import ChatComposer from "./components/chat-composer.vue";
import CreateSessionDialog from "./components/create-session-dialog.vue";
import KnowledgeBindingDialog from "./components/knowledge-binding-dialog.vue";

defineOptions({ name: "AiChat" });

const sessions = ref<ChatSession[]>([]);
const messages = ref<ChatViewMessage[]>([]);
const question = ref("");
const selectedSessionId = ref<number | null>(null);
const sessionLoading = ref(false);
const messageLoading = ref(false);
const creating = ref(false);
const deletingSessionId = ref<number | null>(null);
const deletingMessageId = ref<number | null>(null);
const createVisible = ref(false);
const bindingVisible = ref(false);
const bindingSaving = ref(false);
const knowledgeBases = ref<KnowledgeBase[]>([]);
const loadingKnowledgeBases = ref(false);
const pagination = reactive({ current: 1, size: 20, total: 0 });
const userStore = useUserStoreHook();

let mounted = true;
let listSequence = 0;
let messageSequence = 0;
let listInFlight = false;
let pendingListRefresh = false;
let pendingSelectedSessionId: number | null = null;
let refreshInFlight = false;
let refreshOwner = 0;
let messageLoadingOwner = 0;
let pendingManualRefresh = false;
const deletedSessionIds = new Set<number>();

function currentSession(sessionId: number): boolean { return mounted && selectedSessionId.value === sessionId; }
function clearMessages(): void { messages.value = []; }
function invalidateHistory(): void { ++messageSequence; }
function selectedSession(): ChatSession | undefined { return sessions.value.find(item => item.id === selectedSessionId.value); }
const canQuery = computed(() => userStore.hasPermission("ai:chat:query"));
const activeSession = computed(() => selectedSession());
const activeKnowledgeBaseIds = computed(() => activeSession.value?.knowledgeBaseIds
  ?? (activeSession.value?.knowledgeBaseId ? [activeSession.value.knowledgeBaseId] : []));
const activeKnowledgeBaseLabel = computed(() => {
  const ids = activeKnowledgeBaseIds.value;
  if (ids.length === 0) return "通用 AI 对话";
  if (ids.length === 1) return knowledgeBases.value.find(base => base.id === ids[0])?.name ?? `知识库 ${ids[0]}`;
  return `${ids.length} 个知识库`;
});

const chatStream = useChatStream({
  messages,
  selectedSessionId,
  isMounted: () => mounted,
  invalidateHistory,
  fetchHistory: async sessionId => listChatMessages(sessionId)
});
const canSend = computed(() => {
  const session = selectedSession();
  return canQuery.value
    && chatStream.canAcceptInput.value
    && !chatStream.serverBusy.value
    && !messageLoading.value
    && !!session
    && Number.isSafeInteger(session.id)
    && session.id > 0
    && (session.knowledgeBaseIds ?? (session.knowledgeBaseId ? [session.knowledgeBaseId] : []))
      .every(id => Number.isSafeInteger(id) && id > 0);
});

async function openKnowledgeBinding(): Promise<void> {
  if (!activeSession.value || chatStream.streaming.value || bindingSaving.value) return;
  const targetId = activeSession.value.id;
  await loadKnowledgeBases();
  if (mounted && selectedSessionId.value === targetId && activeSession.value?.id === targetId) {
    bindingVisible.value = true;
  }
}

async function saveKnowledgeBinding(knowledgeBaseIds: number[]): Promise<void> {
  const targetId = selectedSessionId.value;
  if (targetId === null || bindingSaving.value || chatStream.streaming.value) return;
  bindingSaving.value = true;
  try {
    const updated = await replaceChatSessionKnowledgeBases(targetId, knowledgeBaseIds);
    if (!mounted || selectedSessionId.value !== targetId) return;
    const index = sessions.value.findIndex(item => item.id === targetId);
    if (index >= 0) sessions.value[index] = updated;
    bindingVisible.value = false;
    message.success(knowledgeBaseIds.length ? "会话知识库已更新" : "已切换为通用 AI 对话");
  } catch {
    // Request layer displays the safe error.
  } finally {
    if (mounted) bindingSaving.value = false;
  }
}

function refreshMessages(): void {
  const sessionId = selectedSessionId.value;
  if (sessionId === null) return;
  // Never let a history response clobber a live temporary stream.
  if (chatStream.streaming.value || chatStream.cancelling.value || chatStream.draining.value) return;
  // When a recovery attempt is outstanding, route the refresh through the
  // composable's reconciliation so the attempt is updated/cleared together with
  // the message list — the page and composable must not diverge.
  const attempt = chatStream.attempt.value;
  if (attempt && (attempt.status === RecoveryStatus.UNCERTAIN || attempt.status === RecoveryStatus.RETRYABLE)) {
    chatStream.recheck(attempt);
    return;
  }
  if (refreshInFlight) { pendingManualRefresh = true; return; }
  void forceHistoryRefresh(sessionId);
}

async function forceHistoryRefresh(sessionId: number): Promise<void> {
  const expectedSession = sessionId;
  // Take ownership tokens. refreshOwner guards the manual-refresh lock
  // (refreshInFlight); messageLoadingOwner guards the shared messageLoading flag.
  // A stale request releases neither if a newer request has taken over.
  const owner = ++refreshOwner;
  const loadOwner = ++messageLoadingOwner;
  refreshInFlight = true;
  const sequence = ++messageSequence;
  messageLoading.value = true;
  try {
    const result = await listChatMessages(sessionId);
    if (!mounted || sequence !== messageSequence || selectedSessionId.value !== expectedSession) return;
    messages.value = result.map(toChatViewMessage);
  } catch {
    if (mounted && sequence === messageSequence && selectedSessionId.value === expectedSession) {
      // Keep existing messages on failure — never flash blank.
      message.warning("刷新消息失败，已保留当前内容。");
    }
  } finally {
    if (!mounted) return;
    // Only the latest refresh owner may clear the refresh lock and replay.
    if (owner === refreshOwner) {
      refreshInFlight = false;
      if (pendingManualRefresh && selectedSessionId.value === expectedSession) {
        pendingManualRefresh = false;
        void forceHistoryRefresh(expectedSession);
      }
    }
    // Only the latest message-loading owner may turn off the indicator.
    // This stops an older refresh from clearing the flag while a newer
    // session load (or newer refresh) is still running.
    if (loadOwner === messageLoadingOwner) {
      messageLoading.value = false;
    }
  }
}

function retryAttempt(): void {
  const target = chatStream.attempt.value;
  if (!target || target.status !== RecoveryStatus.RETRYABLE) return;
  const session = selectedSession();
  if (!session) return;
  if (chatStream.retry(target)) question.value = "";
}

function recheckAttempt(): void {
  const target = chatStream.attempt.value;
  if (!target) return;
  if (target.status !== RecoveryStatus.UNCERTAIN && target.status !== RecoveryStatus.RETRYABLE && target.status !== RecoveryStatus.RECHECKING) return;
  chatStream.recheck(target);
}

async function loadSessions(force = false): Promise<void> {
  if (listInFlight) { if (force) pendingListRefresh = true; return; }
  const sequence = ++listSequence;
  const current = pagination.current;
  const size = pagination.size;
  listInFlight = true;
  sessionLoading.value = true;
  try {
    const result = await listChatSessions(current, size);
    if (!mounted || sequence !== listSequence || current !== pagination.current || size !== pagination.size) return;
    const visibleRecords = result.records.filter(item => !deletedSessionIds.has(item.id));
    sessions.value = visibleRecords;
    pagination.total = Math.max(0, result.total - (result.records.length - visibleRecords.length));
    if (pendingSelectedSessionId !== null) {
      const targetSessionId = pendingSelectedSessionId;
      if (visibleRecords.some(item => item.id === targetSessionId)) {
        pendingSelectedSessionId = null;
        if (selectedSessionId.value !== targetSessionId) void selectSession(targetSessionId, "automatic");
      } else if (!pendingListRefresh) {
        pendingSelectedSessionId = null;
      }
    } else if (selectedSessionId.value !== null && !visibleRecords.some(item => item.id === selectedSessionId.value)) {
      chatStream.cancel("session-change");
      selectedSessionId.value = null;
      invalidateHistory();
      clearMessages();
    }
  } catch {
    if (!pendingListRefresh) pendingSelectedSessionId = null;
  } finally {
    if (!mounted || sequence !== listSequence) return;
    listInFlight = false;
    sessionLoading.value = false;
    if (pendingListRefresh) { pendingListRefresh = false; void loadSessions(); }
  }
}

async function selectSession(sessionId: number, source: "user" | "automatic" = "user"): Promise<void> {
  // The binding dialog edits the currently selected session. Keep that target stable
  // until the dialog is closed so selections cannot leak from session A into session B.
  if ((bindingVisible.value || bindingSaving.value) && selectedSessionId.value !== sessionId) return;
  if (source === "user" && pendingSelectedSessionId !== null && sessionId !== pendingSelectedSessionId) pendingSelectedSessionId = null;
  if (selectedSessionId.value === sessionId && !messageLoading.value) return;
  if (selectedSessionId.value !== sessionId) {
    chatStream.cancel("session-change");
    // A pending manual refresh belongs to the previous session; a new session
    // load must not carry it over, or the new session will fire a stray replay.
    pendingManualRefresh = false;
  }
  selectedSessionId.value = sessionId;
  clearMessages();
  const sequence = ++messageSequence;
  // Claim ownership of the shared messageLoading flag. Only the latest session
  // load may clear it, so an older refresh (or a superseded load) cannot turn
  // off the indicator while the current load is still in flight.
  const loadOwner = ++messageLoadingOwner;
  messageLoading.value = true;
  try {
    const result = await listChatMessages(sessionId);
    if (!currentSession(sessionId) || sequence !== messageSequence) return;
    messages.value = result.map(toChatViewMessage);
  } catch {
    if (currentSession(sessionId) && sequence === messageSequence) clearMessages();
  } finally {
    if (currentSession(sessionId) && sequence === messageSequence && loadOwner === messageLoadingOwner) {
      messageLoading.value = false;
    }
  }
}

async function loadKnowledgeBases(): Promise<void> {
  if (knowledgeBases.value.length || loadingKnowledgeBases.value) return;
  loadingKnowledgeBases.value = true;
  try {
    const result = await listKnowledgeBases({ current: 1, size: 100 });
    if (mounted) knowledgeBases.value = result.records;
  } catch {
    // A session may still be created, but it cannot be queried until bound to a knowledge base.
  } finally { if (mounted) loadingKnowledgeBases.value = false; }
}

function openCreate(): void { createVisible.value = true; }
async function createSession(request: CreateChatSessionRequest): Promise<void> {
  if (creating.value) return;
  creating.value = true;
  try {
    const created = await createChatSession(request);
    if (!mounted) return;
    createVisible.value = false;
    pagination.current = 1;
    pendingSelectedSessionId = created.id;
    await loadSessions(true);
    if (mounted) await selectSession(created.id, "automatic");
    message.success("会话已创建。");
  } catch {
    // The request layer has already displayed a safe error message.
  } finally { if (mounted) creating.value = false; }
}

async function removeSession(session: ChatSession): Promise<void> {
  const targetSessionId = session.id;
  if (deletingSessionId.value !== null) return;
  // A session undergoing background recovery must not be deleted: its history
  // is still needed to observe the terminal assistant state and lift the
  // user-level server-busy barrier. The UI keeps its delete button disabled,
  // but guard here too in case the event arrives from another path.
  if (chatStream.backgroundRecoverySessionId.value === targetSessionId) {
    message.warning("会话正在后台核对生成结果，暂不可删除。");
    return;
  }
  deletingSessionId.value = targetSessionId;
  try {
    await ElMessageBox.confirm(`确定删除会话“${session.title.trim() || "未命名会话"}”吗？`, "删除确认", { type: "warning" });
  } catch {
    if (mounted && deletingSessionId.value === targetSessionId) deletingSessionId.value = null;
    return;
  }
  try {
    if (selectedSessionId.value === targetSessionId) chatStream.cancel("session-delete");
    deletedSessionIds.add(targetSessionId);
    await deleteChatSession(targetSessionId);
    if (!mounted) return;
    const wasSelected = selectedSessionId.value === targetSessionId;
    sessions.value = sessions.value.filter(item => item.id !== targetSessionId);
    pagination.total = Math.max(0, pagination.total - 1);
    if (wasSelected) {
      selectedSessionId.value = null;
      invalidateHistory();
      clearMessages();
      const next = sessions.value[0];
      if (next) await selectSession(next.id, "automatic");
    }
    if (sessions.value.length === 0 && pagination.current > 1) pagination.current -= 1;
    void loadSessions(true);
    message.success("会话已删除。");
  } catch {
    deletedSessionIds.delete(targetSessionId);
    // The request layer has already displayed a safe error message.
  } finally { if (mounted && deletingSessionId.value === targetSessionId) deletingSessionId.value = null; }
}

async function copyMessage(item: ChatViewMessage): Promise<void> {
  try {
    if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
    const text = item.role === ChatMessageRole.ASSISTANT ? answerBody(item.content) : item.content;
    await navigator.clipboard.writeText(text);
    if (mounted) message.success("消息已复制");
  } catch {
    if (mounted) message.error("复制失败，请检查浏览器剪贴板权限。");
  }
}

function resendMessage(item: ChatViewMessage): void {
  if (item.role !== ChatMessageRole.USER || item.sessionId !== selectedSessionId.value || !canSend.value) return;
  chatStream.send(item.content, selectedSession());
}

async function removeAssistantMessage(item: ChatViewMessage): Promise<void> {
  if (item.role !== ChatMessageRole.ASSISTANT || typeof item.id !== "number" || deletingMessageId.value !== null) return;
  const targetSessionId = selectedSessionId.value;
  const targetMessageId = item.id;
  if (targetSessionId === null || item.sessionId !== targetSessionId || chatStream.streaming.value) return;
  deletingMessageId.value = targetMessageId;
  try {
    await ElMessageBox.confirm("确定删除这条 AI 回复吗？此操作不会删除对应的问题。", "删除回复", { type: "warning" });
  } catch {
    if (mounted && deletingMessageId.value === targetMessageId) deletingMessageId.value = null;
    return;
  }
  try {
    await deleteChatMessage(targetSessionId, targetMessageId);
    if (!currentSession(targetSessionId)) return;
    messages.value = messages.value.filter(messageItem => messageItem.id !== targetMessageId);
    message.success("AI 回复已删除");
    await forceHistoryRefresh(targetSessionId);
  } catch {
    // The request layer has already displayed a safe error message.
  } finally {
    if (mounted && deletingMessageId.value === targetMessageId) deletingMessageId.value = null;
  }
}

function sendQuestion(): void {
  if (!canSend.value || !question.value.trim()) return;
  const pendingQuestion = question.value;
  if (chatStream.send(pendingQuestion, selectedSession())) question.value = "";
}

function changePage(current: number): void { pagination.current = current; void loadSessions(true); }
function changeSize(size: number): void { pagination.size = size; pagination.current = 1; void loadSessions(true); }

onMounted(() => { void loadSessions(); });
onBeforeRouteLeave(() => { chatStream.cancel("route-leave"); });
onUnmounted(() => {
  chatStream.cancel("unmount");
  mounted = false;
  ++listSequence;
  invalidateHistory();
  pendingListRefresh = false;
  pendingSelectedSessionId = null;
  pendingManualRefresh = false;
});
</script>

<template>
  <main v-auth="'ai:chat:list'" class="chat-page">
    <SessionList :sessions="sessions" :selected-session-id="selectedSessionId" :loading="sessionLoading" :deleting-session-id="deletingSessionId" :locked-session-id="chatStream.backgroundRecoverySessionId.value" :current="pagination.current" :size="pagination.size" :total="pagination.total" @create="openCreate" @select="selectSession" @delete="removeSession" @refresh="loadSessions(true)" @page-change="changePage" @size-change="changeSize" />
    <section class="chat-workspace">
      <header class="chat-workspace__header">
        <div>
          <span class="chat-workspace__eyebrow">知识库智能助手</span>
          <h1>{{ activeSession?.title?.trim() || "知识问答" }}</h1>
        </div>
        <button
          class="knowledge-status"
          :class="{ muted: activeKnowledgeBaseIds.length === 0 }"
          type="button"
          :disabled="!activeSession || chatStream.streaming.value || bindingSaving"
          title="调整当前会话的知识库"
          @click="openKnowledgeBinding"
        >
          <i />
          {{ activeKnowledgeBaseLabel }}
          <span class="knowledge-status__edit">调整</span>
        </button>
      </header>
      <MessageHistory
        :messages="messages"
        :loading="messageLoading"
        :selected-session-id="selectedSessionId"
        :streaming="chatStream.streaming.value"
        :syncing="chatStream.syncing.value"
        :cancelling="chatStream.cancelling.value"
        :draining="chatStream.draining.value"
        :can-accept-input="chatStream.canAcceptInput.value"
        :deleting-message-id="deletingMessageId"
        :attempt="chatStream.attempt.value"
        @refresh="refreshMessages"
        @retry="retryAttempt"
        @recheck="recheckAttempt"
        @copy="copyMessage"
        @delete="removeAssistantMessage"
        @resend="resendMessage"
      />
      <ChatComposer v-model="question" :streaming="chatStream.streaming.value" :can-send="canSend" :max-length="4000" @send="sendQuestion" @stop="chatStream.cancel('user')" />
    </section>
    <CreateSessionDialog v-model="createVisible" :knowledge-bases="knowledgeBases" :loading-knowledge-bases="loadingKnowledgeBases" :creating="creating" @opened="loadKnowledgeBases" @create="createSession" />
    <KnowledgeBindingDialog v-model="bindingVisible" :knowledge-bases="knowledgeBases" :selected-ids="activeKnowledgeBaseIds" :loading="loadingKnowledgeBases" :saving="bindingSaving" @save="saveKnowledgeBinding" />
  </main>
</template>

<style scoped lang="scss">
.chat-page {
  height: calc(100vh - 106px);
  min-height: 560px;
  display: grid;
  grid-template-columns: 276px minmax(0, 1fr);
  overflow: hidden;
  background: #fff;
  border: 1px solid #dce7f0;
  border-radius: 8px;
  box-shadow: 0 12px 30px rgba(35, 72, 103, 0.07);
}

.chat-workspace {
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  background: #f7fafe;
}

.chat-workspace__header {
  min-height: 76px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 24px;
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid #e2ebf2;
}

.chat-workspace__eyebrow {
  display: block;
  margin-bottom: 3px;
  color: #66809a;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0;
}

.chat-workspace__header h1 {
  max-width: min(560px, 54vw);
  margin: 0;
  overflow: hidden;
  color: #173956;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 9px;
  color: #42647f;
  font-size: 12px;
  background: #eef6fb;
  border-radius: 6px;
  white-space: nowrap;
  border: 0;
  cursor: pointer;
}

.knowledge-status:not(:disabled):hover { background: #e2f0f9; color: #245f8d; }
.knowledge-status:disabled { cursor: default; opacity: 0.7; }
.knowledge-status__edit { padding-left: 2px; color: #2b78b8; font-weight: 600; }

.knowledge-status i {
  width: 7px;
  height: 7px;
  background: #2d9f78;
  border-radius: 50%;
}

.knowledge-status.muted i {
  background: #9aabba;
}

@media (max-width: 760px) {
  .chat-page {
    height: auto;
    min-height: calc(100vh - 88px);
    grid-template-columns: 1fr;
    grid-template-rows: 230px minmax(520px, 1fr);
  }

  .chat-workspace__header {
    min-height: 66px;
    padding: 12px 16px;
  }

  .chat-workspace__header h1 {
    max-width: 48vw;
  }
}
</style>
