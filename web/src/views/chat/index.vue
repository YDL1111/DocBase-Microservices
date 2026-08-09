<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { ElMessageBox } from "element-plus";
import { createChatSession, deleteChatSession, listChatMessages, listChatSessions } from "@/api/chat";
import { listKnowledgeBases } from "@/api/knowledge";
import type { ChatSession, CreateChatSessionRequest, KnowledgeBase } from "@/api/types";
import { message } from "@/utils/message";
import { useUserStoreHook } from "@/store/modules/user";
import { useChatStream } from "./composables/use-chat-stream";
import { toChatViewMessage, type ChatViewMessage } from "./chat-ui";
import SessionList from "./components/session-list.vue";
import MessageHistory from "./components/message-history.vue";
import ChatComposer from "./components/chat-composer.vue";
import CreateSessionDialog from "./components/create-session-dialog.vue";

defineOptions({ name: "AiChat" });

const sessions = ref<ChatSession[]>([]);
const messages = ref<ChatViewMessage[]>([]);
const question = ref("");
const selectedSessionId = ref<number | null>(null);
const sessionLoading = ref(false);
const messageLoading = ref(false);
const creating = ref(false);
const deletingSessionId = ref<number | null>(null);
const createVisible = ref(false);
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

function currentSession(sessionId: number): boolean { return mounted && selectedSessionId.value === sessionId; }
function clearMessages(): void { messages.value = []; }
function invalidateHistory(): void { ++messageSequence; }
function selectedSession(): ChatSession | undefined { return sessions.value.find(item => item.id === selectedSessionId.value); }
const canQuery = computed(() => userStore.hasPermission("ai:chat:query"));

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
    && !messageLoading.value
    && !!session
    && Number.isSafeInteger(session.knowledgeBaseId)
    && (session.knowledgeBaseId ?? 0) > 0;
});

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
    sessions.value = result.records;
    pagination.total = result.total;
    if (pendingSelectedSessionId !== null) {
      const targetSessionId = pendingSelectedSessionId;
      if (result.records.some(item => item.id === targetSessionId)) {
        pendingSelectedSessionId = null;
        if (selectedSessionId.value !== targetSessionId) void selectSession(targetSessionId, "automatic");
      } else if (!pendingListRefresh) {
        pendingSelectedSessionId = null;
      }
    } else if (selectedSessionId.value !== null && !result.records.some(item => item.id === selectedSessionId.value)) {
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
  if (source === "user" && pendingSelectedSessionId !== null && sessionId !== pendingSelectedSessionId) pendingSelectedSessionId = null;
  if (selectedSessionId.value === sessionId && !messageLoading.value) return;
  if (selectedSessionId.value !== sessionId) chatStream.cancel("session-change");
  selectedSessionId.value = sessionId;
  clearMessages();
  const sequence = ++messageSequence;
  messageLoading.value = true;
  try {
    const result = await listChatMessages(sessionId);
    if (!currentSession(sessionId) || sequence !== messageSequence) return;
    messages.value = result.map(toChatViewMessage);
  } catch {
    if (currentSession(sessionId) && sequence === messageSequence) clearMessages();
  } finally {
    if (currentSession(sessionId) && sequence === messageSequence) messageLoading.value = false;
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
  deletingSessionId.value = targetSessionId;
  try {
    await ElMessageBox.confirm(`确定删除会话“${session.title.trim() || "未命名会话"}”吗？`, "删除确认", { type: "warning" });
  } catch {
    if (mounted && deletingSessionId.value === targetSessionId) deletingSessionId.value = null;
    return;
  }
  try {
    if (selectedSessionId.value === targetSessionId) chatStream.cancel("session-delete");
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
    // The request layer has already displayed a safe error message.
  } finally { if (mounted && deletingSessionId.value === targetSessionId) deletingSessionId.value = null; }
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
});
</script>

<template>
  <main v-auth="'ai:chat:list'" class="chat-page">
    <SessionList :sessions="sessions" :selected-session-id="selectedSessionId" :loading="sessionLoading" :deleting-session-id="deletingSessionId" :current="pagination.current" :size="pagination.size" :total="pagination.total" @create="openCreate" @select="selectSession" @delete="removeSession" @refresh="loadSessions(true)" @page-change="changePage" @size-change="changeSize" />
    <section class="chat-workspace">
      <MessageHistory :messages="messages" :loading="messageLoading" :selected-session-id="selectedSessionId" />
      <ChatComposer v-model="question" :streaming="chatStream.streaming.value" :can-send="canSend" :max-length="4000" @send="sendQuestion" @stop="chatStream.cancel('user')" />
    </section>
    <CreateSessionDialog v-model="createVisible" :knowledge-bases="knowledgeBases" :loading-knowledge-bases="loadingKnowledgeBases" :creating="creating" @opened="loadKnowledgeBases" @create="createSession" />
  </main>
</template>

<style scoped lang="scss">
.chat-page { display: grid; grid-template-columns: 310px minmax(0, 1fr); height: calc(100vh - 100px); min-height: 520px; overflow: hidden; background: var(--el-bg-color); border-radius: 8px; }
.chat-workspace { display: grid; grid-template-rows: minmax(0, 1fr) auto; min-width: 0; min-height: 0; }
@media (max-width: 760px) { .chat-page { grid-template-columns: 1fr; grid-template-rows: 260px minmax(0, 1fr); height: calc(100vh - 88px); }.session-list { border-right: 0; border-bottom: 1px solid var(--el-border-color-light); } }
</style>
