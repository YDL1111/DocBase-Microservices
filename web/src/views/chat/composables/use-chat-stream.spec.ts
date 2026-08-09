import { beforeEach, describe, expect, it, vi } from "vitest";
import { ref } from "vue";
import { ChatMessageRole, ChatMessageStatus, type ChatSession } from "@/api/types";

const { stream, notices } = vi.hoisted(() => ({ stream: vi.fn(), notices: { error: vi.fn(), info: vi.fn(), warning: vi.fn() } }));
vi.mock("@/api/chat-stream", () => ({
  streamChat: (...args: unknown[]) => stream(...args),
  ChatStreamClientError: class ChatStreamClientError extends Error { constructor(readonly code: string) { super(code); } }
}));
vi.mock("@/utils/message", () => ({ message: notices }));

import { useChatStream } from "./use-chat-stream";

const session = (): ChatSession => ({ id: 1, userId: 8, knowledgeBaseId: 3, title: "A", status: 1, createdAt: "now", updatedAt: "now" });
const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; };

function setup() {
  const messages = ref<any[]>([]);
  const selectedSessionId = ref<number | null>(1);
  let mounted = true;
  const invalidateHistory = vi.fn();
  const fetchHistory = vi.fn().mockResolvedValue([]);
  const chat = useChatStream({ messages, selectedSessionId, isMounted: () => mounted, invalidateHistory, fetchHistory });
  return { messages, selectedSessionId, invalidateHistory, fetchHistory, chat, unmount: () => { mounted = false; } };
}

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
    await vi.waitFor(() => expect(state.messages.value.some(item => item.content === "old persisted")).toBe(false));
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

  it("rejects empty questions and invalid or unbound sessions before starting a stream", async () => {
    const { chat } = setup();
    expect(chat.send("   ", session())).toBe(false);
    expect(chat.send("question", { ...session(), knowledgeBaseId: null })).toBe(false);
    expect(stream).not.toHaveBeenCalled();
    expect(notices.warning).toHaveBeenCalledOnce();
  });
});
