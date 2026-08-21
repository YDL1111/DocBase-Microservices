import { beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick, ref, watchEffect } from "vue";
import { ChatMessageRole, ChatMessageStatus, type ChatSession } from "@/api/types";

const { stream, notices } = vi.hoisted(() => ({ stream: vi.fn(), notices: { error: vi.fn(), info: vi.fn(), warning: vi.fn() } }));
vi.mock("@/api/chat-stream", () => ({
  streamChat: (...args: unknown[]) => stream(...args),
  ChatStreamClientError: class ChatStreamClientError extends Error { constructor(readonly code: string, readonly status?: number) { super(code); } }
}));
vi.mock("@/utils/message", () => ({ message: notices }));

import { useChatStream } from "./use-chat-stream";
import { RecoveryStatus, classifyRecovery } from "../chat-ui";

const session = (): ChatSession => ({ id: 1, userId: 8, knowledgeBaseId: 3, knowledgeBaseIds: [3], title: "A", status: 1, createdAt: "now", updatedAt: "now" });
const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; };

interface SetupOptions {
  backgroundPollInterval?: number;
  /** Inject a small value (e.g. 60) to keep deadline tests fast. */
  backgroundRecoveryMaxMs?: number;
}

function setup(options: SetupOptions = {}) {
  const messages = ref<any[]>([]);
  const selectedSessionId = ref<number | null>(1);
  let mounted = true;
  const invalidateHistory = vi.fn();
  const fetchHistory = vi.fn().mockResolvedValue([]);
  const chat = useChatStream({
    messages, selectedSessionId, isMounted: () => mounted, invalidateHistory, fetchHistory,
    backgroundPollInterval: options.backgroundPollInterval,
    backgroundRecoveryMaxMs: options.backgroundRecoveryMaxMs
  });
  return { messages, selectedSessionId, invalidateHistory, fetchHistory, chat, unmount: () => { mounted = false; } };
}

// clientRequestId 必须与 send() 生成的真实 id 一致，否则 reconcile 找不到匹配的 USER，
// 会走 RETRYABLE 分支而非 STREAMING 分支。调用方应在 send() 后从
// state.messages.value[0].clientRequestId 取出真实 id，再传入本工厂。
const streamingHistory = (rid: string) => [
  { id: 5, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
  { id: 6, sessionId: 1, userId: 8, role: 2, content: "", status: ChatMessageStatus.STREAMING, createdAt: "t" }
];
const completedHistory = (rid: string) => [
  { id: 5, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
  { id: 6, sessionId: 1, userId: 8, role: 2, content: "done", status: ChatMessageStatus.COMPLETED, createdAt: "t" }
];

describe("useChatStream", () => {
  beforeEach(() => {
    stream.mockReset();
    notices.error.mockReset();
    notices.info.mockReset();
    notices.warning.mockReset();
  });

  it("immediately adds a USER message and updates one assistant message incrementally", async () => {
    const events: any[] = [];
    stream.mockImplementation(async (_request: unknown, options: any) => {
      events.push(options.onEvent);
      await options.onEvent({ type: "token", data: "first" });
      await options.onEvent({ type: "token", data: " second" });
      await options.onEvent({ type: "sources", data: [{ document_id: 2, file_name: "guide.pdf", page: 4 }] });
      return { terminal: "error", error: { code: "RAG_ERROR", message: "internal detail" } };
    });
    const { chat, messages, invalidateHistory } = setup();
    expect(chat.send("  question  ", session())).toBe(true);
    await vi.waitFor(() => expect(messages.value[1]).toMatchObject({ role: ChatMessageRole.ASSISTANT, content: "first second", status: ChatMessageStatus.FAILED, sources: [{ document_id: 2 }] }));
    expect(invalidateHistory).toHaveBeenCalledOnce();
    expect(messages.value).toHaveLength(2);
    expect(messages.value[0]).toMatchObject({ role: ChatMessageRole.USER, content: "question", status: ChatMessageStatus.COMPLETED });
    expect(notices.error).toHaveBeenCalledOnce();
    expect(notices.error).not.toHaveBeenCalledWith(expect.stringContaining("internal detail"));
  });

  it("notifies Vue rendering for every token before the stream completes", async () => {
    const streamDone = deferred<any>();
    let onEvent!: (event: any) => Promise<void>;
    stream.mockImplementation((_request: unknown, options: any) => {
      onEvent = options.onEvent;
      return streamDone.promise;
    });
    const state = setup();
    const rendered: string[] = [];
    watchEffect(() => rendered.push(state.messages.value[1]?.content ?? ""));

    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(onEvent).toBeTypeOf("function"));
    await onEvent({ type: "token", data: "first" });
    await nextTick();
    expect(rendered).toContain("first");

    await onEvent({ type: "token", data: " second" });
    await nextTick();
    expect(rendered).toContain("first second");
    expect(state.chat.streaming.value).toBe(true);

    streamDone.resolve({ terminal: "error", error: { code: "RAG_ERROR", message: "x" } });
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(false));
  });

  it("shows an actionable message when bound knowledge bases have no searchable documents", async () => {
    stream.mockResolvedValue({
      terminal: "error",
      error: { code: "NO_SEARCHABLE_DOCUMENTS", message: "server detail" }
    });
    const { chat, messages } = setup();

    expect(chat.send("question", session())).toBe(true);

    await vi.waitFor(() => expect(messages.value[1]).toMatchObject({
      status: ChatMessageStatus.FAILED,
      errorCode: "NO_SEARCHABLE_DOCUMENTS"
    }));
    expect(notices.error).toHaveBeenCalledWith(expect.stringContaining("已发布且入库成功"));
    expect(notices.error).not.toHaveBeenCalledWith(expect.stringContaining("server detail"));
  });

  it("refreshes persisted history after done and keeps the temporary answer until it arrives", async () => {
    const history = deferred<any[]>();
    stream.mockImplementation(async (_request: unknown, options: any) => {
      await options.onEvent({ type: "token", data: "answer" });
      return { terminal: "done" };
    });
    const state = setup();
    state.fetchHistory.mockReturnValue(history.promise);
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.fetchHistory).toHaveBeenCalledWith(1));
    expect(state.messages.value[1]).toMatchObject({ content: "answer", status: ChatMessageStatus.COMPLETED, temporary: true });
    history.resolve([{ id: 9, sessionId: 1, userId: 8, role: 2, content: "persisted", status: 2, createdAt: "later" }]);
    await vi.waitFor(() => expect(state.messages.value).toEqual([expect.objectContaining({ id: 9, content: "persisted" })]));
    expect(state.chat.streaming.value).toBe(false);
  });

  it("accepts a new question while the previous done history refresh is still settling", async () => {
    const history = deferred<any[]>();
    const second = deferred<any>();
    let call = 0;
    stream.mockImplementation(async (_request: unknown, options: any) => {
      call += 1;
      if (call === 1) {
        await options.onEvent({ type: "token", data: "first answer" });
        return { terminal: "done" };
      }
      return second.promise;
    });
    const state = setup();
    state.fetchHistory.mockReturnValue(history.promise);
    expect(state.chat.send("first", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.settling.value).toBe(true));
    expect(state.chat.streaming.value).toBe(false);
    expect(state.chat.canAcceptInput.value).toBe(true);
    expect(state.chat.send("second", session())).toBe(true);
    await vi.waitFor(() => expect(stream).toHaveBeenCalledTimes(2));
    history.resolve([{ id: 9, sessionId: 1, userId: 8, role: 2, content: "old persisted", status: 2, createdAt: "later" }]);
    await vi.waitFor(() => expect(state.messages.value.some((item: any) => item.content === "old persisted")).toBe(false));
    expect(state.chat.streaming.value).toBe(true);
    second.resolve({ terminal: "error", error: { code: "RAG_ERROR", message: "x" } });
    await vi.waitFor(() => expect(state.chat.settling.value).toBe(false));
  });

  it("keeps the error drain active until EOF, then accepts the next question", async () => {
    const result = deferred<any>();
    let onEvent!: (event: any) => Promise<void>;
    let call = 0;
    stream.mockImplementation((_request: unknown, options: any) => {
      call += 1;
      if (call === 1) {
        onEvent = options.onEvent;
        return result.promise;
      }
      return Promise.resolve({ terminal: "error", error: { code: "RAG_ERROR", message: "second" } });
    });
    const state = setup();
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(onEvent).toBeTypeOf("function"));
    await onEvent({ type: "error", data: { code: "RAG_ERROR", message: "private detail" } });
    expect(state.messages.value[1]).toMatchObject({ status: ChatMessageStatus.FAILED, errorCode: "RAG_ERROR" });
    expect(state.chat.streaming.value).toBe(false);
    expect(state.chat.settling.value).toBe(true);
    expect(state.chat.draining.value).toBe(true);
    expect(state.chat.canAcceptInput.value).toBe(false);
    expect(state.chat.send("second question", session())).toBe(false);
    expect(stream).toHaveBeenCalledTimes(1);
    state.chat.cancel("user");
    expect(state.messages.value[1].status).toBe(ChatMessageStatus.FAILED);
    expect(notices.info).not.toHaveBeenCalled();
    result.resolve({ terminal: "error", error: { code: "RAG_ERROR", message: "private detail" } });
    await vi.waitFor(() => expect(state.chat.draining.value).toBe(false));
    expect(state.chat.canAcceptInput.value).toBe(true);
    expect(state.chat.send("second question", session())).toBe(true);
    await vi.waitFor(() => expect(stream).toHaveBeenCalledTimes(2));
  });

  it("marks an EOF without terminal event as failed rather than successful", async () => {
    stream.mockRejectedValue(Object.assign(new Error("incomplete"), { code: "STREAM_INCOMPLETE" }));
    const { chat, messages } = setup();
    expect(chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(messages.value[1]).toMatchObject({ status: ChatMessageStatus.FAILED, errorCode: "STREAM_INCOMPLETE" }));
  });

  it("aborts on user stop and reports the cancellation only once", async () => {
    const hold = deferred<any>();
    stream.mockImplementation((_request: unknown, options: any) => new Promise((_resolve, reject) => {
      options.signal.addEventListener("abort", () => reject(Object.assign(new Error("abort"), { code: "CLIENT_CANCELLED" })));
      void hold;
    }));
    const { chat, messages } = setup();
    expect(chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(chat.streaming.value).toBe(true));
    chat.cancel("user");
    expect(messages.value[1]).toMatchObject({ status: ChatMessageStatus.CANCELLED, errorCode: "CLIENT_CANCELLED" });
    expect(notices.info).toHaveBeenCalledTimes(1);
  });

  it("ignores late A events and history after switching to B", async () => {
    const gate = deferred<any>();
    let onEvent!: (event: any) => Promise<void>;
    stream.mockImplementation(async (_request: unknown, options: any) => { onEvent = options.onEvent; return gate.promise; });
    const state = setup();
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(true));
    state.selectedSessionId.value = 2;
    state.chat.cancel("session-change");
    await onEvent({ type: "token", data: "late A" });
    gate.resolve({ terminal: "done" });
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(false));
    expect(state.messages.value[1].content).toBe("");
    expect(state.fetchHistory).not.toHaveBeenCalled();
  });

  it("waits for a user-cancelled stream to settle before starting another one", async () => {
    const first = deferred<any>();
    const second = deferred<any>();
    let call = 0;
    stream.mockImplementation((_request: unknown, options: any) => {
      call += 1;
      return call === 1 ? first.promise : second.promise;
    });
    const state = setup();
    expect(state.chat.send("one", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(true));
    state.chat.cancel("user");
    expect(state.chat.cancelling.value).toBe(true);
    expect(state.chat.canAcceptInput.value).toBe(false);
    expect(state.chat.send("two", session())).toBe(false);
    expect(stream).toHaveBeenCalledTimes(1);
    first.resolve({ terminal: "done" });
    await vi.waitFor(() => expect(state.chat.cancelling.value).toBe(false));
    expect(state.chat.canAcceptInput.value).toBe(true);
    expect(state.chat.send("two", session())).toBe(true);
    await vi.waitFor(() => expect(stream).toHaveBeenCalledTimes(2));
    expect(state.chat.streaming.value).toBe(true);
    second.resolve({ terminal: "error", error: { code: "RAG_ERROR", message: "x" } });
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(false));
  });

  it("rejects empty questions but allows a valid session without a knowledge base", async () => {
    stream.mockResolvedValue({ terminal: "done" });
    const { chat } = setup();
    expect(chat.send("   ", session())).toBe(false);
    expect(chat.send("question", { ...session(), knowledgeBaseId: null, knowledgeBaseIds: [] })).toBe(true);
    await vi.waitFor(() => expect(stream).toHaveBeenCalledOnce());
    expect(stream.mock.calls[0][0]).toMatchObject({ sessionId: 1, knowledgeBaseIds: [], question: "question" });
    expect(notices.warning).not.toHaveBeenCalled();
  });

  // ---- Phase 4C2A recovery & reconciliation ----

  it("treats STREAM_INCOMPLETE as uncertain, reconciles with history, and on match replaces temporaries", async () => {
    const history = deferred<any[]>();
    stream.mockRejectedValue(Object.assign(new Error("incomplete"), { code: "STREAM_INCOMPLETE" }));
    const state = setup();
    state.fetchHistory.mockReturnValue(history.promise);
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    expect(state.messages.value[1]).toMatchObject({ status: ChatMessageStatus.FAILED, temporary: true });
    const rid = state.messages.value[0].clientRequestId ?? "x";
    history.resolve([
      { id: 5, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
      { id: 6, sessionId: 1, userId: 8, role: 2, content: "persisted answer", status: 2, createdAt: "t" }
    ]);
    await vi.waitFor(() => expect(state.chat.attempt.value).toBeNull());
    expect(state.messages.value.some((m: any) => m.id === 6 && m.content === "persisted answer")).toBe(true);
    expect(state.chat.canAcceptInput.value).toBe(true);
  });

  it("keeps the attempt and allows recheck when the correlated assistant is still STREAMING", async () => {
    const history = deferred<any[]>();
    stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
    const state = setup();
    state.fetchHistory.mockReturnValue(history.promise);
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    const rid = state.messages.value[0].clientRequestId ?? "x";
    // USER 已落库，但关联的 ASSISTANT 仍是 STREAMING → 服务端仍在处理，应保留 attempt 并暴露 recheck。
    history.resolve([
      { id: 5, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
      { id: 6, sessionId: 1, userId: 8, role: 2, content: "", status: ChatMessageStatus.STREAMING, createdAt: "t" }
    ]);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.UNCERTAIN));
    expect(state.chat.attempt.value).not.toBeNull();
    // 后端仍在处理（ASSISTANT 为 STREAMING）时，必须禁用新发送/重试，
    // 否则会清掉原 attempt 并撞上 Redis 并发锁。只允许 recheck。
    expect(state.chat.canAcceptInput.value).toBe(false);
    expect(state.chat.retry(state.chat.attempt.value!)).toBe(false);
    // recheck 仍可用（reconcile 入口会将状态从 RECHECKING 推进到 SYNCING）。
    state.chat.recheck(state.chat.attempt.value!);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
  });

  it("keeps temporaries and exposes retry when reconciliation finds no matching clientRequestId", async () => {
    const history = deferred<any[]>();
    stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
    const state = setup();
    state.fetchHistory.mockReturnValue(history.promise);
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    history.resolve([]);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.RETRYABLE));
    expect(state.messages.value[1]).toMatchObject({ status: ChatMessageStatus.FAILED, temporary: true });
    expect(state.chat.canAcceptInput.value).toBe(true);
  });

  it("retry reuses the original clientRequestId", async () => {
    const history = deferred<any[]>();
    let call = 0;
    stream.mockImplementation(async () => {
      call += 1;
      if (call === 1) throw Object.assign(new Error("net"), { code: "NETWORK_ERROR" });
      return { terminal: "done" };
    });
    const state = setup();
    state.fetchHistory.mockReturnValue(history.promise);
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    const originalId = state.messages.value[0].clientRequestId;
    history.resolve([]);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.RETRYABLE));
    state.fetchHistory.mockResolvedValue([{ id: 1, sessionId: 1, userId: 8, role: 2, content: "ok", status: 2, createdAt: "t" }]);
    expect(state.chat.retry(state.chat.attempt.value!)).toBe(true);
    await vi.waitFor(() => expect(stream).toHaveBeenNthCalledWith(2, expect.objectContaining({ clientRequestId: originalId }), expect.anything()));
  });

  it("a new question generates a different clientRequestId", async () => {
    const ids = new Set<string>();
    stream.mockImplementation(async (request: any) => { ids.add(request.clientRequestId); return { terminal: "done" }; });
    const state = setup();
    state.fetchHistory.mockResolvedValue([]);
    expect(state.chat.send("one", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(false));
    expect(state.chat.send("two", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(false));
    expect(ids.size).toBe(2);
  });

  it("handles DUPLICATE_REQUEST by reconciling history without creating a new id", async () => {
    const history = deferred<any[]>();
    stream.mockImplementation(async (_request: unknown, options: any) => {
      await options.onEvent({ type: "session", data: { sessionId: 1, messageId: 9 } });
      return { terminal: "error", error: { code: "DUPLICATE_REQUEST", message: "该请求正在处理中，请勿重复提交" } };
    });
    const state = setup();
    state.fetchHistory.mockReturnValue(history.promise);
    expect(state.chat.send("question", session())).toBe(true);
    const originalId = state.messages.value[0].clientRequestId;
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    history.resolve([
      { id: 7, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: originalId ?? "x", createdAt: "t" },
      { id: 8, sessionId: 1, userId: 8, role: 2, content: "dup answer", status: 2, createdAt: "t" }
    ]);
    await vi.waitFor(() => expect(state.chat.attempt.value).toBeNull());
    expect(stream).toHaveBeenCalledTimes(1);
  });

  it("does not start a new stream while the previous one is still cancelling", async () => {
    const first = deferred<any>();
    stream.mockImplementation((_request: unknown, options: any) => new Promise((_resolve, reject) => {
      options.signal.addEventListener("abort", () => reject(Object.assign(new Error("abort"), { code: "CLIENT_CANCELLED" })));
      void first;
    }));
    const state = setup();
    state.fetchHistory.mockResolvedValue([]);
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.streaming.value).toBe(true));
    state.chat.cancel("user");
    expect(state.chat.cancelling.value).toBe(true);
    expect(state.chat.canAcceptInput.value).toBe(false);
    expect(state.chat.send("second", session())).toBe(false);
    first.resolve({ terminal: "error", error: { code: "NETWORK_ERROR", message: "x" } });
    await vi.waitFor(() => expect(state.chat.cancelling.value).toBe(false));
  });

  it("coalesces repeated recheck clicks into a single in-flight request", async () => {
    const phases: Array<ReturnType<typeof deferred<any[]>>> = [];
    let call = 0;
    const state = setup();
    state.fetchHistory.mockImplementation(async () => {
      call += 1;
      const idx = call - 1;
      while (phases.length <= idx) phases.push(deferred<any[]>());
      return phases[idx].promise;
    });
    stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    // send() 触发第一次 reconcile → call 1
    phases[0].resolve([]);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.RETRYABLE));
    expect(call).toBe(1);
    const target = state.chat.attempt.value!;
    state.chat.recheck(target);
    state.chat.recheck(target);
    state.chat.recheck(target);
    await vi.waitFor(() => expect(call).toBe(2));
    // 仅一次 recheck 真正发出（pendingRecheck 守卫合并了三次点击）
    phases[1].resolve([]);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.RETRYABLE));
    expect(call).toBe(2);
  });

  it("allows a second serial recheck after the first one completes", async () => {
    let call = 0;
    const state = setup();
    state.fetchHistory.mockImplementation(async () => { call += 1; return []; });
    stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.RETRYABLE));
    expect(call).toBe(1);
    // 第一次 recheck → call 2，完成后 pendingRecheck 复位，允许第二次 recheck。
    state.chat.recheck(state.chat.attempt.value!);
    await vi.waitFor(() => expect(call).toBe(2));
    await vi.waitFor(() => expect(state.chat.attempt.value?.pendingRecheck).toBe(false));
    state.chat.recheck(state.chat.attempt.value!);
    await vi.waitFor(() => expect(call).toBe(3));
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.RETRYABLE));
  });

  it("classifies HTTP 403 as a hard failure but HTTP 500 as uncertain", () => {
    expect(classifyRecovery("HTTP_ERROR", 403)).toBe("failed");
    expect(classifyRecovery("HTTP_ERROR", 401)).toBe("failed");
    expect(classifyRecovery("HTTP_ERROR", 429)).toBe("failed");
    expect(classifyRecovery("HTTP_ERROR", 500)).toBe("uncertain");
    expect(classifyRecovery("HTTP_ERROR", 503)).toBe("uncertain");
    expect(classifyRecovery("HTTP_ERROR")).toBe("uncertain");
  });

  it("clears the stale attempt when a manual refresh observes terminal history", async () => {
    // 模拟页面层：存在一个 RETRYABLE attempt，用户点刷新后刷新结果交给 reconcile，
    // 由 composable 统一把消息替换为终态并清除 attempt，避免页面与 composable 状态分叉。
    const first = deferred<any[]>();
    const state = setup();
    state.fetchHistory.mockReturnValue(first.promise);
    stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    const rid = state.messages.value[0].clientRequestId ?? "x";
    first.resolve([]);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.RETRYABLE));
    // 页面刷新：复用 reconciliation（recheck），刷新结果包含终态 ASSISTANT。
    const second = deferred<any[]>();
    state.fetchHistory.mockReturnValue(second.promise);
    state.chat.recheck(state.chat.attempt.value!);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    second.resolve([
      { id: 5, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
      { id: 6, sessionId: 1, userId: 8, role: 2, content: "persisted", status: 2, createdAt: "t" }
    ]);
    await vi.waitFor(() => expect(state.chat.attempt.value).toBeNull());
    expect(state.messages.value.some((m: any) => m.content === "persisted")).toBe(true);
  });

  it("keeps the server-busy barrier across a session switch until the backend task finishes", async () => {
    vi.useFakeTimers();
    try {
      const history = deferred<any[]>();
      stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
      // 注入短轮询间隔，让 fake timer 能快速推进到下一轮轮询。
      const state = setup({ backgroundPollInterval: 50 });
      state.fetchHistory.mockReturnValue(history.promise);
      expect(state.chat.send("question", session())).toBe(true);
      await vi.advanceTimersByTimeAsync(0);
      expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING);
      const rid = state.messages.value[0].clientRequestId ?? "x";
      // 对账发现 ASSISTANT 仍为 STREAMING → 保留 attempt，禁用输入，并启动后台轮询。
      history.resolve([
        { id: 5, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
        { id: 6, sessionId: 1, userId: 8, role: 2, content: "", status: ChatMessageStatus.STREAMING, createdAt: "t" }
      ]);
      await vi.advanceTimersByTimeAsync(0);
      expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.UNCERTAIN);
      expect(state.chat.canAcceptInput.value).toBe(false);
      expect(state.chat.serverBusy.value).toBe(true);
      // 用户切到会话 B：session 级 attempt 被清除，但用户级屏障必须保留。
      state.selectedSessionId.value = 2;
      state.chat.cancel("session-change");
      expect(state.chat.attempt.value).toBeNull();
      expect(state.chat.serverBusy.value).toBe(true);
      expect(state.chat.canAcceptInput.value).toBe(false);
      // 后台轮询命中终态 → 屏障解除，输入恢复。
      state.fetchHistory.mockResolvedValue([
        { id: 5, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
        { id: 6, sessionId: 1, userId: 8, role: 2, content: "done", status: ChatMessageStatus.COMPLETED, createdAt: "t" }
      ]);
      await vi.waitFor(() => expect(state.chat.serverBusy.value).toBe(false));
      expect(state.chat.canAcceptInput.value).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it("ignores stale reconciliation result after switching session", async () => {
    const historyA = deferred<any[]>();
    stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
    const state = setup();
    state.fetchHistory.mockReturnValue(historyA.promise);
    expect(state.chat.send("question", session())).toBe(true);
    await vi.waitFor(() => expect(state.chat.attempt.value?.status).toBe(RecoveryStatus.SYNCING));
    const tempAssistantId = state.messages.value[1].id;
    state.selectedSessionId.value = 2;
    state.chat.cancel("session-change");
    const rid = state.messages.value[0].clientRequestId ?? "x";
    historyA.resolve([{ id: 1, sessionId: 1, userId: 8, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" }]);
    await vi.waitFor(() => expect(state.chat.canAcceptInput.value).toBe(true));
    // Switching away from session A must invalidate A's recovery attempt so its
    // stale reconciliation can never write into session B.
    expect(state.chat.attempt.value).toBeNull();
    expect(state.chat.syncing.value).toBe(false);
  });

  it("locks the recovering session id while the background barrier is live", async () => {
    vi.useFakeTimers();
    try {
      const history = deferred<any[]>();
      stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
      const state = setup({ backgroundPollInterval: 50 });
      // 所有 fetchHistory 先返回同一挂起的 deferred；解析后再切换为终态。
      state.fetchHistory.mockReturnValue(history.promise);
      expect(state.chat.send("question", session())).toBe(true);
      await vi.advanceTimersByTimeAsync(0);
      const rid = state.messages.value[0].clientRequestId ?? "x";
      // 对账命中 STREAMING → 启动后台恢复，锁定 session id = 1。
      history.resolve(streamingHistory(rid));
      await vi.advanceTimersByTimeAsync(0);
      expect(state.chat.backgroundRecoverySessionId.value).toBe(1);
      expect(state.chat.serverBusy.value).toBe(true);
      // 切到 B 不影响后台锁定的 session id。
      state.selectedSessionId.value = 2;
      state.chat.cancel("session-change");
      expect(state.chat.backgroundRecoverySessionId.value).toBe(1);
      // 切换 mock：后台轮询命中终态 → 锁定解除。
      state.fetchHistory.mockResolvedValue(completedHistory(rid));
      await vi.advanceTimersByTimeAsync(50);
      expect(state.chat.serverBusy.value).toBe(false);
      expect(state.chat.backgroundRecoverySessionId.value).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it("lifts the barrier after the deadline even when the assistant stays STREAMING forever", async () => {
    vi.useFakeTimers();
    try {
      const history = deferred<any[]>();
      stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
      // 轮询间隔 50ms、期限 120ms：始终 STREAMING → 超时后必须解除屏障。
      const state = setup({ backgroundPollInterval: 50, backgroundRecoveryMaxMs: 120 });
      state.fetchHistory.mockReturnValue(history.promise);
      expect(state.chat.send("question", session())).toBe(true);
      await vi.advanceTimersByTimeAsync(0);
      const rid = state.messages.value[0].clientRequestId ?? "x";
      history.resolve(streamingHistory(rid));
      await vi.advanceTimersByTimeAsync(0);
      expect(state.chat.serverBusy.value).toBe(true);
      expect(state.chat.canAcceptInput.value).toBe(false);
      expect(state.chat.backgroundRecoverySessionId.value).toBe(1);
      // 不切换 mock：后续轮询持续返回 STREAMING，但超过期限后屏障必须解除。
      await vi.advanceTimersByTimeAsync(200);
      expect(state.chat.serverBusy.value).toBe(false);
      expect(state.chat.canAcceptInput.value).toBe(true);
      expect(state.chat.backgroundRecoverySessionId.value).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it("lifts the barrier on 404/403 instead of polling forever", async () => {
    vi.useFakeTimers();
    try {
      const history = deferred<any[]>();
      stream.mockRejectedValue(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
      const state = setup({ backgroundPollInterval: 50 });
      state.fetchHistory.mockReturnValue(history.promise);
      expect(state.chat.send("question", session())).toBe(true);
      await vi.advanceTimersByTimeAsync(0);
      const rid = state.messages.value[0].clientRequestId ?? "x";
      history.resolve(streamingHistory(rid));
      await vi.advanceTimersByTimeAsync(0);
      expect(state.chat.serverBusy.value).toBe(true);
      // 切换 mock：会话已被删除 → 后续轮询持续 404，应立即解除屏障。
      state.fetchHistory.mockRejectedValue(Object.assign(new Error("gone"), { response: { status: 404 } }));
      await vi.advanceTimersByTimeAsync(50);
      expect(state.chat.serverBusy.value).toBe(false);
      expect(state.chat.canAcceptInput.value).toBe(true);
      expect(state.chat.backgroundRecoverySessionId.value).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});
