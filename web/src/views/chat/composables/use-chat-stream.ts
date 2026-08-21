import { reactive, ref, type Ref } from "vue";
import { ChatStreamClientError, streamChat, type ChatSource } from "@/api/chat-stream";
import { ChatMessageRole, ChatMessageStatus, type ChatMessage, type ChatSession } from "@/api/types";
import { message } from "@/utils/message";
import {
  RecoveryStatus,
  classifyRecovery,
  recoveryStatusText,
  type ChatViewMessage,
  type RecoveryAttempt,
  type RecoveryStatusKind
} from "../chat-ui";

const MAX_QUESTION_LENGTH = 4000;

type CancelReason = "user" | "session-change" | "session-delete" | "route-leave" | "unmount" | "replacement" | "authentication";

interface StreamContext {
  generation: number;
  sessionId: number;
  controller: AbortController;
  assistant: ChatViewMessage;
  userMessage: ChatViewMessage;
  terminalReceived: boolean;
  clientRequestId: string;
  question: string;
  knowledgeBaseIds: number[];
}

export interface UseChatStreamOptions {
  messages: Ref<ChatViewMessage[]>;
  selectedSessionId: Ref<number | null>;
  isMounted: () => boolean;
  invalidateHistory: () => void;
  fetchHistory: (sessionId: number) => Promise<ChatMessage[]>;
  /** Interval (ms) between background recovery polls. Inject a small value in tests. */
  backgroundPollInterval?: number;
  /** Max lifetime (ms) of the background recovery barrier. Defaults to 130s. */
  backgroundRecoveryMaxMs?: number;
}

function requestId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  throw new Error("The browser does not support cryptographically secure request identifiers");
}

function timestamp(): string { return new Date().toISOString(); }

function safeFailureMessage(error: unknown): string {
  if (error instanceof ChatStreamClientError) {
    if (error.code === "STREAM_INCOMPLETE" || error.code === "EMPTY_STREAM") return "回答已中断，请稍后重试。";
    if (error.code === "CLIENT_CANCELLED") return "已停止生成。";
    if (error.code === "UNAUTHENTICATED") return "登录状态已失效，请重新登录。";
  }
  return "暂时无法生成回答，请稍后重试。";
}

function safeServerFailureMessage(code: string): string {
  if (code === "NO_SEARCHABLE_DOCUMENTS") return "所选知识库没有已发布且入库成功的可见文档，请先到知识库发布文档或等待入库完成。";
  if (code === "KNOWLEDGE_SCOPE_FORBIDDEN") return "当前账号无权检索所选知识库，请检查知识库成员权限。";
  if (code === "KNOWLEDGE_SCOPE_UNAVAILABLE") return "知识库检索服务暂时不可用，请稍后重试。";
  return safeFailureMessage(undefined);
}

export function useChatStream(options: UseChatStreamOptions) {
  const streaming = ref(false);
  const settling = ref(false);
  const draining = ref(false);
  const cancelling = ref(false);
  const canAcceptInput = ref(true);
  const syncing = ref(false);
  const attempt = ref<RecoveryAttempt | null>(null);
  // User-level barrier: true while a backend task is believed to still be
  // running for ANY session (reconciliation observed a STREAMING assistant).
  // Unlike `attempt` (session-specific, cleared on session switch), this
  // survives session switches and is lifted only when the background poll
  // observes a terminal assistant state. This prevents sending into session B
  // while the Redis user-level stream lock for session A is still held.
  const serverBusy = ref(false);
  let generation = 0;
  let active: StreamContext | null = null;
  let historySequence = 0;
  let pendingHistoryRefresh = false;
  // Background recovery tracks a STREAMING request independently of which
  // session is currently selected, so reconciliation can continue after the
  // user navigates away. Enough data is kept to restore the visible attempt if
  // the user returns before the barrier lifts.
  let backgroundRecovery: { sessionId: number; clientRequestId: string; question: string; userMessageId: number | string; assistantMessageId: number | string; generation: number; startedAt: number } | null = null;
  let backgroundTimer: ReturnType<typeof setTimeout> | null = null;
  let backgroundInFlight = false;
  const BACKGROUND_POLL_INTERVAL = options.backgroundPollInterval ?? 2500;
  /**
   * Maximum lifetime of the background recovery barrier. The backend's Redis
   * user-level stream lock TTL is 120s (ChatConstants.STREAM_LOCK_TTL_SECONDS);
   * once that elapses the lock is certainly gone even if the backend crashed.
   * We add a small grace margin so the barrier never outlives the lock.
   */
  const BACKGROUND_RECOVERY_MAX_MS = options.backgroundRecoveryMaxMs ?? 130_000;
  /** Session currently being recovered in the background, or null. Exposed so the
   * page can prevent that session from being deleted while the barrier is live. */
  const backgroundRecoverySessionId = ref<number | null>(null);

  function isCurrent(context: StreamContext): boolean {
    return options.isMounted()
      && active === context
      && generation === context.generation
      && !context.controller.signal.aborted
      && options.selectedSessionId.value === context.sessionId;
  }

  function clearAttempt(next: RecoveryAttempt | null): void {
    if (attempt.value && next && attempt.value.generation === next.generation) {
      attempt.value = next;
      return;
    }
    if (!next) attempt.value = null;
    else attempt.value = next;
  }

  /** Marks a terminal failure. Returns the recovery status kind to apply. */
  function markFailure(context: StreamContext, code: string, status: number | undefined, notify: boolean): RecoveryStatusKind {
    if (!isCurrent(context) || context.terminalReceived) return attempt.value?.status ?? RecoveryStatus.NONE;
    context.terminalReceived = true;
    const recovery = classifyRecovery(code, status);
    context.assistant.status = ChatMessageStatus.FAILED;
    context.assistant.errorCode = code;
    streaming.value = false;
    settling.value = true;
    draining.value = true;
    canAcceptInput.value = false;
    if (notify) message.error(code === "STREAM_INCOMPLETE"
      ? safeFailureMessage(new ChatStreamClientError("STREAM_INCOMPLETE"))
      : safeServerFailureMessage(code));
    if (recovery === "uncertain") {
      const next: RecoveryAttempt = {
        sessionId: context.sessionId,
        question: context.question,
        clientRequestId: context.clientRequestId,
        generation: context.generation,
        userMessageId: context.userMessage.id,
        assistantMessageId: context.assistant.id,
        status: RecoveryStatus.UNCERTAIN,
        pendingRecheck: false
      };
      clearAttempt(next);
      return RecoveryStatus.UNCERTAIN;
    }
    return RecoveryStatus.NONE;
  }

  function cancel(reason: CancelReason): void {
    const context = active;
    // Any session-level teardown invalidates the in-flight recovery attempt so a
    // stale reconciliation can never write into a different session or resurrect
    // a deleted one.
    if (reason === "session-change" || reason === "session-delete" || reason === "route-leave" || reason === "unmount") {
      invalidateRecovery();
      // On unmount the component is gone — stop polling. On session switch the
      // background recovery keeps running so the barrier is respected.
      if (reason === "unmount") clearBackgroundRecovery();
    }
    if (!context) return;
    if (context.terminalReceived) {
      if (context.assistant.status === ChatMessageStatus.COMPLETED) {
        ++generation;
        active = null;
        settling.value = false;
        draining.value = false;
        cancelling.value = false;
        canAcceptInput.value = true;
      }
      return;
    }
    context.terminalReceived = true;
    streaming.value = false;
    settling.value = true;
    cancelling.value = true;
    canAcceptInput.value = false;
    if (reason === "user") {
      context.assistant.status = ChatMessageStatus.CANCELLED;
      context.assistant.errorCode = "CLIENT_CANCELLED";
      message.info("已停止生成。");
    }
    context.controller.abort();
  }

  /**
   * Clears the session-specific recovery attempt and its loading state. This is
   * called on session switch/delete/route-leave/unmount. It removes the visible
   * attempt from the (now previous) session, but it must NOT lift the user-level
   * `serverBusy` barrier: the backend task for the previous session may still be
   * running and holding the Redis user-level stream lock. Only the background
   * poll may clear `serverBusy`, and only after observing a terminal assistant.
   */
  function invalidateRecovery(): void {
    if (attempt.value !== null || syncing.value) {
      ++historySequence;
      attempt.value = null;
      syncing.value = false;
      // Restore input only if the server is not busy with a background recovery.
      // If it is, the barrier stays until pollBackgroundRecovery observes terminal.
      if (!serverBusy.value) canAcceptInput.value = true;
    }
  }

  /** True while `target` is still the live attempt for its session. */
  function attemptIsLive(target: RecoveryAttempt): boolean {
    const cur = attempt.value;
    return options.isMounted()
      && cur !== null
      && cur.generation === target.generation
      && cur.sessionId === target.sessionId
      && options.selectedSessionId.value === target.sessionId;
  }

  /**
   * Finds the assistant message correlated with the target request. The backend
   * persists a USER message and a STREAMING assistant placeholder together in the
   * same transaction before calling RAG, so the assistant is the ASSISTANT-role
   * message whose id is the smallest id greater than the matched user message.
   */
  function findCorrelatedAssistant(history: ChatMessage[], userMessageId: number | string): ChatMessage | undefined {
    const userId = typeof userMessageId === "number" ? userMessageId : -1;
    let best: ChatMessage | undefined;
    let bestId = Infinity;
    for (const item of history) {
      if (item.role !== ChatMessageRole.ASSISTANT) continue;
      const itemId = typeof item.id === "number" ? item.id : Infinity;
      if (itemId > userId && itemId < bestId) {
        best = item;
        bestId = itemId;
      }
    }
    return best;
  }

  function clearBackgroundRecovery(): void {
    backgroundRecovery = null;
    backgroundRecoverySessionId.value = null;
    if (backgroundTimer !== null) { clearTimeout(backgroundTimer); backgroundTimer = null; }
  }

  /**
   * Lifts the user-level barrier and clears all background recovery state.
   * Used when the barrier must come down without reconciling the original
   * session's visible messages (timeout, session gone, or permanent error).
   */
  function liftBarrierSafe(reason: "timeout" | "gone"): void {
    clearBackgroundRecovery();
    serverBusy.value = false;
    canAcceptInput.value = true;
    if (reason === "timeout") {
      message.warning("仍在生成中，已临时开放发送。结果将在生成完成后同步。");
    } else {
      message.warning("原会话已不可访问，已恢复发送。");
    }
  }

  function startBackgroundRecovery(target: RecoveryAttempt): void {
    backgroundRecovery = {
      sessionId: target.sessionId,
      clientRequestId: target.clientRequestId,
      question: target.question,
      userMessageId: target.userMessageId,
      assistantMessageId: target.assistantMessageId,
      generation: target.generation,
      startedAt: Date.now()
    };
    backgroundRecoverySessionId.value = target.sessionId;
    if (!serverBusy.value) serverBusy.value = true;
    scheduleBackgroundPoll();
  }

  function scheduleBackgroundPoll(): void {
    if (backgroundTimer !== null) clearTimeout(backgroundTimer);
    backgroundTimer = setTimeout(() => { void pollBackgroundRecovery(); }, BACKGROUND_POLL_INTERVAL);
  }

  async function pollBackgroundRecovery(): Promise<void> {
    if (backgroundInFlight || !backgroundRecovery) return;
    // Deadline guard: the backend's Redis user-level stream lock TTL is 120s.
    // If we still haven't observed a terminal state past the grace period, the
    // lock is certainly gone (or the message is permanently stuck). Lifting the
    // barrier is then strictly safer than blocking the user forever.
    if (Date.now() - backgroundRecovery.startedAt >= BACKGROUND_RECOVERY_MAX_MS) {
      liftBarrierSafe("timeout");
      return;
    }
    backgroundInFlight = true;
    const recovery = backgroundRecovery;
    try {
      const history = await options.fetchHistory(recovery.sessionId);
      const userMatch = history.find(item => item.clientRequestId === recovery.clientRequestId);
      const assistant = userMatch ? findCorrelatedAssistant(history, userMatch.id) : undefined;
      if (!assistant || assistant.status === ChatMessageStatus.STREAMING) {
        // Still processing on the backend — keep the barrier raised and retry.
        scheduleBackgroundPoll();
        return;
      }
      // Terminal state reached: the backend task (and its Redis lock) is done.
      // serverBusy + canAcceptInput are user-level concerns tied to the barrier,
      // so they lift regardless of which session is currently selected.
      clearBackgroundRecovery();
      serverBusy.value = false;
      canAcceptInput.value = true;
      // If the user is back on the original session, reconcile its visible state.
      if (options.selectedSessionId.value === recovery.sessionId) {
        options.messages.value = history.map(m => ({ ...m, sources: m.sourcesJson ? safeSources(m.sourcesJson) : [] }));
        attempt.value = null;
        syncing.value = false;
      }
    } catch (err) {
      // A missing session (deleted by the user) or a permission error is permanent:
      // polling will never succeed, so lift the barrier instead of blocking forever.
      const status = (err as { response?: { status?: number } } | undefined)?.response?.status;
      if (status === 403 || status === 404) {
        liftBarrierSafe("gone");
        return;
      }
      // Transient failure (network, 5xx) — keep polling rather than dropping the barrier.
      scheduleBackgroundPoll();
    } finally {
      backgroundInFlight = false;
    }
  }

  /**
   * Reconciles the current uncertain attempt with the persisted history. Idempotent
   * on the attempt generation: a newer attempt or session change invalidates the run.
   *
   * Because the backend writes a USER + STREAMING assistant placeholder before RAG,
   * merely finding the USER is not enough. We must inspect the correlated assistant:
   *  - terminal (COMPLETED / FAILED / CANCELLED): authoritative, replace temporaries;
   *  - STREAMING: still processing — keep the attempt and allow recheck;
   *  - no USER at all: treat as not-yet-persisted and expose retry.
   */
  async function reconcile(context: StreamContext, target: RecoveryAttempt): Promise<void> {
    const sequence = ++historySequence;
    if (!attemptIsLive(target)) return;
    target.status = RecoveryStatus.SYNCING;
    // 注意：不要在此处复位 target.pendingRecheck。该标志由 recheck() 在调用前设为 true，
    // 必须保持 true 直到某个终态分支完成，否则 recheck 的"合并重复点击"守卫会失效。
    attempt.value = { ...target };
    syncing.value = true;
    let history: ChatMessage[];
    try {
      history = await options.fetchHistory(target.sessionId);
    } catch {
      if (!attemptIsLive(target)) return;
      target.status = RecoveryStatus.RETRYABLE;
      target.pendingRecheck = false;
      attempt.value = { ...target };
      syncing.value = false;
      settling.value = false;
      draining.value = false;
      cancelling.value = false;
      canAcceptInput.value = true;
      message.error("历史核对失败，已保留当前内容，请稍后重试。");
      return;
    }
    if (!attemptIsLive(target) || sequence !== historySequence) return;
    const userMatch = history.find(item => item.clientRequestId === target.clientRequestId);
    if (!userMatch) {
      // No persisted USER yet — keep temporaries and expose retry.
      target.status = RecoveryStatus.RETRYABLE;
      target.pendingRecheck = false;
      attempt.value = { ...target };
      syncing.value = false;
      settling.value = false;
      draining.value = false;
      cancelling.value = false;
      canAcceptInput.value = true;
      return;
    }
    const assistant = findCorrelatedAssistant(history, userMatch.id);
    if (!assistant || assistant.status === ChatMessageStatus.STREAMING) {
      // Still processing on the server. Keep the attempt so the UI shows a
      // processing state and offers recheck; never surface this as terminal.
      //
      // The backend writes the assistant as STREAMING BEFORE running RAG and
      // releases its Redis user-level stream lock ONLY AFTER the assistant
      // reaches a terminal state. So even though the front-end transport has
      // ended (active === null), the backend task — and its concurrency lock —
      // may still be held. Raise a user-level barrier and continue polling in
      // the background so the lock cannot be tripped by a send in ANY session.
      target.status = RecoveryStatus.UNCERTAIN;
      target.pendingRecheck = false;
      attempt.value = { ...target };
      syncing.value = false;
      settling.value = false;
      draining.value = false;
      cancelling.value = false;
      canAcceptInput.value = false;
      startBackgroundRecovery(target);
      return;
    }
    // Terminal assistant state (COMPLETED / FAILED / CANCELLED): the persisted
    // history is authoritative — replace temporaries.
    options.messages.value = history.map(m => ({
      ...m,
      sources: m.sourcesJson ? safeSources(m.sourcesJson) : []
    }));
    clearAttempt(null);
    active = null;
    streaming.value = false;
    settling.value = false;
    draining.value = false;
    cancelling.value = false;
    syncing.value = false;
    canAcceptInput.value = true;
  }

  async function complete(context: StreamContext): Promise<void> {
    if (!isCurrent(context)) return;
    if (!context.assistant.content.trim()) {
      markFailure(context, "EMPTY_RESPONSE", undefined, true);
      return;
    }
    context.terminalReceived = true;
    context.assistant.status = ChatMessageStatus.COMPLETED;
    streaming.value = false;
    settling.value = true;
    canAcceptInput.value = true;
    try {
      const history = await options.fetchHistory(context.sessionId);
      if (!isCurrent(context)) return;
      options.messages.value = history.map(m => ({ ...m, sources: m.sourcesJson ? safeSources(m.sourcesJson) : [] }));
    } catch {
      // Keep the completed temporary message. The session can be refreshed manually.
    } finally {
      if (isCurrent(context)) {
        active = null;
        streaming.value = false;
        settling.value = false;
        draining.value = false;
        cancelling.value = false;
        canAcceptInput.value = true;
      }
    }
  }

  async function consume(context: StreamContext): Promise<void> {
    try {
      const outcome = await streamChat({
        sessionId: context.sessionId,
        knowledgeBaseIds: context.knowledgeBaseIds,
        question: context.question,
        clientRequestId: context.clientRequestId
      }, {
        signal: context.controller.signal,
        onEvent: event => {
          if (!isCurrent(context) || context.terminalReceived) return;
          if (event.type === "session") {
            if (event.data.sessionId !== context.sessionId) {
              markFailure(context, "SESSION_MISMATCH", undefined, true);
              context.controller.abort();
            }
          } else if (event.type === "token") {
            context.assistant.content += event.data;
          } else if (event.type === "sources") {
            context.assistant.sources = event.data as ChatSource[];
          } else if (event.type === "error") {
            handleServerError(context, event.data.code);
          }
        }
      });
      if (!isCurrent(context)) return;
      if (outcome.terminal === "done") await complete(context);
      else handleServerError(context, outcome.error.code);
    } catch (error) {
      if (!isCurrent(context)) return;
      if (error instanceof ChatStreamClientError && error.code === "CLIENT_CANCELLED") return;
      const status = error instanceof ChatStreamClientError ? error.status : undefined;
      const code = error instanceof ChatStreamClientError
        ? error.code
        : typeof error === "object" && error !== null && typeof (error as { code?: unknown }).code === "string"
          ? (error as { code: string }).code
          : "STREAM_ERROR";
      handleClientError(context, code, status);
    } finally {
      if (active === context && generation === context.generation && context.terminalReceived && context.assistant.status !== ChatMessageStatus.COMPLETED) {
        active = null;
        streaming.value = false;
        settling.value = false;
        draining.value = false;
        cancelling.value = false;
        canAcceptInput.value = true;
      }
    }
  }

  /** Handles an SSE error event or terminal outcome from the stream client. */
  function handleServerError(context: StreamContext, code: string): void {
    if (code === "DUPLICATE_REQUEST") {
      // Backend already has this requestId. Reconcile with history; do NOT create a new id.
      const target: RecoveryAttempt = {
        sessionId: context.sessionId,
        question: context.question,
        clientRequestId: context.clientRequestId,
        generation: context.generation,
        userMessageId: context.userMessage.id,
        assistantMessageId: context.assistant.id,
        status: RecoveryStatus.SYNCING,
        pendingRecheck: false
      };
      clearAttempt(target);
      context.terminalReceived = true;
      streaming.value = false;
      settling.value = true;
      draining.value = true;
      canAcceptInput.value = false;
      void reconcile(context, target);
      return;
    }
    const recovery = markFailure(context, code, undefined, true);
    if (recovery === RecoveryStatus.UNCERTAIN) {
      const current = attempt.value;
      if (current) void reconcile(context, current);
    }
  }

  /** Handles a transport-level failure caught in the consume() catch block. */
  function handleClientError(context: StreamContext, code: string, status?: number): void {
    const recovery = markFailure(context, code, status, true);
    if (recovery === RecoveryStatus.UNCERTAIN) {
      const current = attempt.value;
      if (current) void reconcile(context, current);
    }
  }

  function safeSources(value: string): ChatSource[] {
    try {
      const parsed: unknown = JSON.parse(value);
      return Array.isArray(parsed) ? parsed.filter(isSafeSource) : [];
    } catch { return []; }
  }
  function isSafeSource(value: unknown): value is ChatSource {
    if (!value || typeof value !== "object" || Array.isArray(value)) return false;
    const source = value as Record<string, unknown>;
    return Number.isSafeInteger(source.document_id) && (source.document_id as number) > 0
      && (source.file_name === undefined || source.file_name === null || typeof source.file_name === "string")
      && (source.page === undefined || source.page === null || (Number.isSafeInteger(source.page) && (source.page as number) > 0));
  }

  function send(question: string, session: ChatSession | undefined): boolean {
    const normalizedQuestion = question.trim();
    if (!canAcceptInput.value || streaming.value || draining.value || cancelling.value || !normalizedQuestion || normalizedQuestion.length > MAX_QUESTION_LENGTH) return false;
    const knowledgeBaseIds = session?.knowledgeBaseIds ?? (session?.knowledgeBaseId ? [session.knowledgeBaseId] : []);
    if (!session || !Number.isSafeInteger(session.id) || session.id < 1
      || knowledgeBaseIds.length > 20
      || knowledgeBaseIds.some(id => !Number.isSafeInteger(id) || id < 1)) {
      message.warning("请选择一个有效会话后再提问。");
      return false;
    }
    let clientRequestId: string;
    try { clientRequestId = requestId(); } catch { message.error("当前浏览器无法安全创建请求，请升级浏览器后重试。"); return false; }
    cancel("replacement");
    options.invalidateHistory();
    const gen = ++generation;
    const userMessage: ChatViewMessage = {
      id: `stream-user-${gen}`,
      sessionId: session.id,
      userId: session.userId,
      role: ChatMessageRole.USER,
      content: normalizedQuestion,
      status: ChatMessageStatus.COMPLETED,
      createdAt: timestamp(),
      clientRequestId,
      temporary: true
    };
    const assistant = reactive<ChatViewMessage>({
      id: `stream-assistant-${gen}`,
      sessionId: session.id,
      userId: session.userId,
      role: ChatMessageRole.ASSISTANT,
      content: "",
      status: ChatMessageStatus.STREAMING,
      createdAt: timestamp(),
      sources: [],
      temporary: true
    });
    const context: StreamContext = {
      generation: gen,
      sessionId: session.id,
      controller: new AbortController(),
      terminalReceived: false,
      clientRequestId,
      question: normalizedQuestion,
      userMessage,
      assistant,
      knowledgeBaseIds
    };
    clearAttempt(null);
    active = context;
    streaming.value = true;
    canAcceptInput.value = false;
    options.messages.value.push(userMessage, assistant);
    void consume(context);
    return true;
  }

  /** User-initiated retry of an uncertain attempt. Reuses the original clientRequestId. */
  function retry(target: RecoveryAttempt): boolean {
    if (!canAcceptInput.value || streaming.value || draining.value || cancelling.value || !options.isMounted()) return false;
    if (!attemptIsLive(target)) return false;
    const session = { id: target.sessionId, userId: 0, knowledgeBaseId: null, knowledgeBaseIds: [], title: "", status: 0, createdAt: "", updatedAt: "" } as ChatSession;
    cancel("replacement");
    options.invalidateHistory();
    const gen = ++generation;
    const userMessage: ChatViewMessage = {
      id: `stream-user-${gen}`,
      sessionId: target.sessionId,
      userId: session.userId,
      role: ChatMessageRole.USER,
      content: target.question,
      status: ChatMessageStatus.COMPLETED,
      createdAt: timestamp(),
      clientRequestId: target.clientRequestId,
      temporary: true
    };
    const assistant = reactive<ChatViewMessage>({
      id: `stream-assistant-${gen}`,
      sessionId: target.sessionId,
      userId: session.userId,
      role: ChatMessageRole.ASSISTANT,
      content: "",
      status: ChatMessageStatus.STREAMING,
      createdAt: timestamp(),
      sources: [],
      temporary: true
    });
    const context: StreamContext = {
      generation: gen,
      sessionId: target.sessionId,
      controller: new AbortController(),
      terminalReceived: false,
      clientRequestId: target.clientRequestId,
      question: target.question,
      userMessage,
      assistant,
      knowledgeBaseIds: []
    };
    clearAttempt(null);
    active = context;
    streaming.value = true;
    canAcceptInput.value = false;
    options.messages.value.push(userMessage, assistant);
    void consume(context);
    return true;
  }

  /** User-initiated recheck of an uncertain/retryable attempt against history. */
  function recheck(target: RecoveryAttempt): void {
    if (!attemptIsLive(target)) return;
    if (target.pendingRecheck) return;
    const context: StreamContext = {
      generation: target.generation,
      sessionId: target.sessionId,
      controller: new AbortController(),
      assistant: { id: target.assistantMessageId, sessionId: target.sessionId, userId: 0, role: ChatMessageRole.ASSISTANT, content: "", status: ChatMessageStatus.STREAMING, createdAt: timestamp(), temporary: true },
      userMessage: { id: target.userMessageId, sessionId: target.sessionId, userId: 0, role: ChatMessageRole.USER, content: target.question, status: ChatMessageStatus.COMPLETED, createdAt: timestamp(), temporary: true },
      terminalReceived: true,
      clientRequestId: target.clientRequestId,
      question: target.question,
      knowledgeBaseIds: []
    };
    target.pendingRecheck = true;
    target.status = RecoveryStatus.RECHECKING;
    attempt.value = { ...target };
    syncing.value = true;
    void reconcile(context, target);
  }

  return {
    streaming, settling, draining, cancelling, canAcceptInput, syncing, serverBusy, attempt,
    backgroundRecoverySessionId, send, cancel, retry, recheck
  };
}
