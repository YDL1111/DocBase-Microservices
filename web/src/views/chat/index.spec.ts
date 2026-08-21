import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import authDirective from "@/directive/permission";

const { listSessions, listMessages, createSession, deleteSession, deleteMessage, replaceKnowledgeBases, listKnowledgeBases, confirm, streamChat, messages, hasPermission } = vi.hoisted(() => ({
  listSessions: vi.fn(), listMessages: vi.fn(), createSession: vi.fn(), deleteSession: vi.fn(), deleteMessage: vi.fn(), confirm: vi.fn(),
  replaceKnowledgeBases: vi.fn(), listKnowledgeBases: vi.fn(),
  streamChat: vi.fn(),
  messages: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }, hasPermission: vi.fn()
}));
vi.mock("@/api/chat", () => ({ listChatSessions: (...args: unknown[]) => listSessions(...args), listChatMessages: (...args: unknown[]) => listMessages(...args), createChatSession: (...args: unknown[]) => createSession(...args), deleteChatSession: (...args: unknown[]) => deleteSession(...args), deleteChatMessage: (...args: unknown[]) => deleteMessage(...args), replaceChatSessionKnowledgeBases: (...args: unknown[]) => replaceKnowledgeBases(...args) }));
vi.mock("@/api/knowledge", () => ({ listKnowledgeBases: (...args: unknown[]) => listKnowledgeBases(...args) }));
vi.mock("@/api/chat-stream", () => ({
  streamChat: (...args: unknown[]) => streamChat(...args),
  ChatStreamClientError: class ChatStreamClientError extends Error { constructor(readonly code: string) { super(code); } }
}));
vi.mock("@/utils/message", () => ({ message: messages }));
vi.mock("element-plus", () => ({ ElMessageBox: { confirm: (...args: unknown[]) => confirm(...args) } }));
vi.mock("@/store/modules/user", () => ({ useUserStoreHook: () => ({ hasPermission }) }));

import ChatPage from "./index.vue";

const SessionList = { name: "SessionList", props: ["sessions", "selectedSessionId", "loading", "deletingSessionId", "lockedSessionId", "current", "size", "total"], emits: ["create", "select", "delete", "refresh", "pageChange", "sizeChange"], template: "<div><slot /></div>" };
const MessageHistory = { name: "MessageHistory", props: ["messages", "loading", "selectedSessionId", "streaming", "syncing", "cancelling", "draining", "canAcceptInput", "deletingMessageId", "attempt"], emits: ["refresh", "retry", "recheck", "copy", "delete", "resend"], template: "<div />" };
const ChatComposer = { name: "ChatComposer", props: ["modelValue", "streaming", "canSend", "maxLength"], emits: ["send", "stop", "update:modelValue"], template: "<div />" };
const CreateSessionDialog = { name: "CreateSessionDialog", props: ["modelValue", "knowledgeBases", "loadingKnowledgeBases", "creating"], emits: ["create", "opened", "update:modelValue"], template: "<div />" };
const KnowledgeBindingDialog = { name: "KnowledgeBindingDialog", props: ["modelValue", "knowledgeBases", "selectedIds", "loading", "saving"], emits: ["save", "update:modelValue"], template: "<div />" };
function result(records: any[] = []) { return { records, total: records.length, current: 1, size: 20, pages: 1 }; }
async function flush() { await nextTick(); await Promise.resolve(); await nextTick(); }
function mountPage() { return mount(ChatPage, { global: { stubs: { SessionList, MessageHistory, ChatComposer, CreateSessionDialog, KnowledgeBindingDialog }, directives: { auth: { mounted() {}, updated() {} } } } }); }

describe("chat history page", () => {
  beforeEach(() => { vi.clearAllMocks(); streamChat.mockReset(); hasPermission.mockReturnValue(true); listKnowledgeBases.mockResolvedValue(result([{ id: 7, name: "知识库 A" }, { id: 8, name: "知识库 B" }])); listSessions.mockResolvedValue(result([{ id: 1, title: "A", knowledgeBaseId: 7, knowledgeBaseIds: [7], updatedAt: "t", userId: 1, status: 1, createdAt: "t" }])); });

  it("allows the header knowledge button to replace one binding with multiple knowledge bases", async () => {
    listMessages.mockResolvedValue([]);
    replaceKnowledgeBases.mockResolvedValue({ id: 1, title: "A", knowledgeBaseId: 7, knowledgeBaseIds: [7, 8], updatedAt: "t", userId: 1, status: 1, createdAt: "t" });
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    await wrapper.find(".knowledge-status").trigger("click"); await flush();
    const dialog = wrapper.findComponent(KnowledgeBindingDialog);
    expect(dialog.props("modelValue")).toBe(true);
    dialog.vm.$emit("save", [7, 8]); await flush();
    expect(replaceKnowledgeBases).toHaveBeenCalledWith(1, [7, 8]);
    expect(wrapper.text()).toContain("2 个知识库");
  });

  it("does not open a stale binding dialog when the selected session changes during knowledge loading", async () => {
    let resolveKnowledge!: (value: ReturnType<typeof result>) => void;
    listKnowledgeBases.mockReset().mockImplementationOnce(() => new Promise(resolve => { resolveKnowledge = resolve; }));
    listSessions.mockResolvedValue(result([
      { id: 1, title: "A", knowledgeBaseId: 7, knowledgeBaseIds: [7], updatedAt: "t", userId: 1, status: 1, createdAt: "t" },
      { id: 2, title: "B", knowledgeBaseId: 8, knowledgeBaseIds: [8], updatedAt: "t", userId: 1, status: 1, createdAt: "t" }
    ]));
    listMessages.mockResolvedValue([]);
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    await wrapper.find(".knowledge-status").trigger("click");
    list.vm.$emit("select", 2); await flush();
    resolveKnowledge(result([{ id: 7, name: "知识库 A" }, { id: 8, name: "知识库 B" }]));
    await flush();
    expect(wrapper.findComponent(MessageHistory).props("selectedSessionId")).toBe(2);
    expect(wrapper.findComponent(KnowledgeBindingDialog).props("modelValue")).toBe(false);
  });

  it("renders sessions returned by the real page request", async () => {
    const wrapper = mountPage(); await flush();
    expect(wrapper.findComponent(SessionList).props("sessions")).toHaveLength(1);
    expect(listSessions).toHaveBeenCalledWith(1, 20);
  });

  it("enables the composer for a valid general chat session without a knowledge base", async () => {
    listSessions.mockResolvedValue(result([{ id: 2, title: "General", knowledgeBaseId: null, updatedAt: "t", userId: 1, status: 1, createdAt: "t" }]));
    listMessages.mockResolvedValue([]);
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 2); await flush();
    expect(wrapper.findComponent(ChatComposer).props("canSend")).toBe(true);
    expect(wrapper.text()).toContain("通用 AI 对话");
  });

  it("prevents duplicate create submission and selects the created session", async () => {
    createSession.mockResolvedValue({ id: 9, title: "N", knowledgeBaseId: null, userId: 1, status: 1, createdAt: "t", updatedAt: "t" });
    listMessages.mockResolvedValue([]);
    const wrapper = mountPage(); await flush();
    const dialog = wrapper.findComponent(CreateSessionDialog);
    dialog.vm.$emit("create", { title: "N", knowledgeBaseId: null });
    dialog.vm.$emit("create", { title: "N", knowledgeBaseId: null });
    await flush(); await flush();
    expect(createSession).toHaveBeenCalledOnce();
    expect(wrapper.findComponent(MessageHistory).props("selectedSessionId")).toBe(9);
  });

  it("keeps the created selection through an older in-flight list response and its pending refresh", async () => {
    let resolveInitial!: (value: ReturnType<typeof result>) => void;
    listSessions.mockReset().mockImplementationOnce(() => new Promise(resolve => { resolveInitial = resolve; }))
      .mockResolvedValueOnce(result([{ id: 9, title: "N", knowledgeBaseId: null, updatedAt: "t", userId: 1, status: 1, createdAt: "t" }]));
    createSession.mockResolvedValue({ id: 9, title: "N", knowledgeBaseId: null, userId: 1, status: 1, createdAt: "t", updatedAt: "t" });
    listMessages.mockResolvedValue([]);
    const wrapper = mountPage(); await nextTick();
    wrapper.findComponent(CreateSessionDialog).vm.$emit("create", { title: "N", knowledgeBaseId: null });
    await flush();
    resolveInitial(result([{ id: 1, title: "old", knowledgeBaseId: null, updatedAt: "t", userId: 1, status: 1, createdAt: "t" }]));
    await flush(); await flush();
    expect(wrapper.findComponent(MessageHistory).props("selectedSessionId")).toBe(9);
    expect(listSessions).toHaveBeenCalledTimes(2);
  });

  it("keeps a later user selection when the pending create refresh fails", async () => {
    let rejectInitial!: (reason: unknown) => void;
    listSessions.mockReset().mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectInitial = reject; }))
      .mockRejectedValueOnce(new Error("refresh failed"))
      .mockResolvedValueOnce(result([
        { id: 9, title: "A", knowledgeBaseId: null, updatedAt: "t", userId: 1, status: 1, createdAt: "t" },
        { id: 2, title: "B", knowledgeBaseId: null, updatedAt: "t", userId: 1, status: 1, createdAt: "t" }
      ]));
    createSession.mockResolvedValue({ id: 9, title: "A", knowledgeBaseId: null, userId: 1, status: 1, createdAt: "t", updatedAt: "t" });
    listMessages.mockResolvedValue([]);
    const wrapper = mountPage(); await nextTick();
    const list = wrapper.findComponent(SessionList);
    wrapper.findComponent(CreateSessionDialog).vm.$emit("create", { title: "A", knowledgeBaseId: null });
    await flush();
    rejectInitial(new Error("old list failed"));
    await flush(); await flush();
    list.vm.$emit("select", 2); await flush();
    list.vm.$emit("refresh"); await flush();
    expect(wrapper.findComponent(MessageHistory).props("selectedSessionId")).toBe(2);
  });

  it("does not let late session A history overwrite selected session B", async () => {
    let resolveA!: (value: any[]) => void;
    listMessages.mockImplementationOnce(() => new Promise(resolve => { resolveA = resolve; })).mockResolvedValueOnce([{ id: 2, sessionId: 2, role: 1, content: "B", status: 2, userId: 1, createdAt: "t" }]);
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    list.vm.$emit("select", 2); await flush();
    resolveA([{ id: 1, sessionId: 1, role: 1, content: "A", status: 2, userId: 1, createdAt: "t" }]); await flush();
    expect(wrapper.findComponent(MessageHistory).props("selectedSessionId")).toBe(2);
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("B");
  });

  it("clears previous messages when the selected session history returns 403/404", async () => {
    listMessages.mockResolvedValueOnce([{ id: 1, sessionId: 1, role: 1, content: "old", status: 2, userId: 1, createdAt: "t" }]).mockRejectedValueOnce(new Error("forbidden"));
    const wrapper = mountPage(); await flush(); const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    list.vm.$emit("select", 2); await flush();
    expect(wrapper.findComponent(MessageHistory).props("messages")).toEqual([]);
  });

  it("does not call delete when confirmation is cancelled", async () => {
    confirm.mockRejectedValueOnce(new Error("cancel"));
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("delete", { id: 1, title: "A" }); await flush();
    expect(deleteSession).not.toHaveBeenCalled();
  });

  it("keeps an active answer running when the delete confirmation is cancelled", async () => {
    let signal!: AbortSignal;
    confirm.mockRejectedValueOnce(new Error("cancel"));
    listMessages.mockResolvedValue([]);
    streamChat.mockImplementation((_request: unknown, options: { signal: AbortSignal }) => {
      signal = options.signal;
      return new Promise(() => {});
    });
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    const composer = wrapper.findComponent(ChatComposer);
    composer.vm.$emit("update:modelValue", "question"); await flush();
    composer.vm.$emit("send"); await flush();
    list.vm.$emit("delete", { id: 1, title: "A", knowledgeBaseId: 7 }); await flush();
    expect(signal.aborted).toBe(false);
    expect(composer.props("streaming")).toBe(true);
  });

  it("locks deletion before confirmation so a second confirm cannot open", async () => {
    let rejectConfirm!: (reason: unknown) => void;
    confirm.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectConfirm = reject; }));
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("delete", { id: 1, title: "A" }); await flush();
    list.vm.$emit("delete", { id: 2, title: "B" }); await flush();
    expect(confirm).toHaveBeenCalledOnce();
    expect(list.props("deletingSessionId")).toBe(1);
    rejectConfirm(new Error("cancel")); await flush();
    expect(list.props("deletingSessionId")).toBeNull();
  });

  it("deletes the captured target even if selection changes while confirmation is open", async () => {
    let approve!: () => void;
    confirm.mockImplementationOnce(() => new Promise<void>(resolve => { approve = resolve; }));
    deleteSession.mockResolvedValue(undefined);
    listMessages.mockResolvedValue([]);
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("delete", { id: 1, title: "A" }); await flush();
    list.vm.$emit("select", 2); await flush();
    approve(); await flush(); await flush();
    expect(deleteSession).toHaveBeenCalledWith(1);
  });

  it("does not let an older session-list response resurrect a deleted session", async () => {
    let resolveStale!: (value: ReturnType<typeof result>) => void;
    listSessions.mockReset()
      .mockResolvedValueOnce(result([{ id: 1, title: "A", knowledgeBaseId: null, updatedAt: "t", userId: 1, status: 1, createdAt: "t" }]))
      .mockImplementationOnce(() => new Promise(resolve => { resolveStale = resolve; }))
      .mockResolvedValueOnce(result([]));
    confirm.mockResolvedValue(undefined);
    deleteSession.mockResolvedValue(undefined);
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("refresh"); await flush();
    list.vm.$emit("delete", { id: 1, title: "A" }); await flush();
    resolveStale(result([{ id: 1, title: "A", knowledgeBaseId: null, updatedAt: "t", userId: 1, status: 1, createdAt: "t" }]));
    await flush(); await flush();
    expect(list.props("sessions")).toEqual([]);
    expect(listSessions).toHaveBeenCalledTimes(3);
  });

  it("deletes one assistant reply and refreshes authoritative history", async () => {
    const user = { id: 10, sessionId: 1, role: 1, content: "question", status: 2, userId: 1, createdAt: "t" };
    const assistant = { id: 11, sessionId: 1, role: 2, content: "answer", status: 2, userId: 1, createdAt: "t" };
    listMessages.mockResolvedValueOnce([user, assistant]).mockResolvedValueOnce([user]);
    confirm.mockResolvedValue(undefined);
    deleteMessage.mockResolvedValue(undefined);
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    const history = wrapper.findComponent(MessageHistory);
    history.vm.$emit("delete", assistant); await flush(); await flush();
    expect(deleteMessage).toHaveBeenCalledWith(1, 11);
    expect(history.props("messages")).toEqual([user]);
    expect(messages.success).toHaveBeenCalledWith("AI 回复已删除");
  });

  it("locks assistant deletion before confirmation", async () => {
    let approve!: () => void;
    const assistant = { id: 11, sessionId: 1, role: 2, content: "answer", status: 2, userId: 1, createdAt: "t" };
    listMessages.mockResolvedValue([assistant]);
    confirm.mockImplementationOnce(() => new Promise<void>(resolve => { approve = resolve; }));
    deleteMessage.mockResolvedValue(undefined);
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    const history = wrapper.findComponent(MessageHistory);
    history.vm.$emit("delete", assistant);
    history.vm.$emit("delete", assistant);
    await flush();
    expect(confirm).toHaveBeenCalledOnce();
    expect(history.props("deletingMessageId")).toBe(11);
    approve(); await flush(); await flush();
  });

  it("copies message text and resends a user question with a fresh request id", async () => {
    const user = { id: 10, sessionId: 1, role: 1, content: "question", status: 2, userId: 1, clientRequestId: "old-request", createdAt: "t" };
    const assistant = { ...user, id: 11, role: 2, content: "answer【来源：private.docx】" };
    listMessages.mockResolvedValue([user]);
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    streamChat.mockResolvedValue({ terminal: "done" });
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    const history = wrapper.findComponent(MessageHistory);
    history.vm.$emit("copy", user); await flush();
    history.vm.$emit("copy", assistant); await flush();
    history.vm.$emit("resend", user); await flush();
    expect(writeText).toHaveBeenNthCalledWith(1, "question");
    expect(writeText).toHaveBeenNthCalledWith(2, "answer");
    expect(streamChat).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: 1,
      question: "question",
      clientRequestId: expect.not.stringMatching(/^old-request$/)
    }), expect.any(Object));
  });

  it("refuses to delete a session that is undergoing background recovery", async () => {
    // 流以 NETWORK_ERROR 失败 → 自动对账发现 ASSISTANT 仍为 STREAMING → 进入后台恢复，
    // 此时被恢复的会话（id=1）不可删除：删除会丢掉核对终态所需的历史。
    // 拦截 streamChat 以捕获 send() 生成的真实 clientRequestId，对账历史必须使用相同 id。
    let rid = "x";
    streamChat.mockImplementation((request: { clientRequestId: string }) => {
      rid = request.clientRequestId;
      return Promise.reject(Object.assign(new Error("net"), { code: "NETWORK_ERROR" }));
    });
    listMessages.mockResolvedValueOnce([{ id: 1, sessionId: 1, role: 1, content: "seed", status: 2, userId: 1, createdAt: "t" }]);
    // 注意：STREAMING = 1（ChatMessageStatus），不是 3（FAILED）。
    const recoveringHistory = () => [
      { id: 1, sessionId: 1, userId: 1, role: 1, content: "question", status: 2, clientRequestId: rid, createdAt: "t" },
      { id: 2, sessionId: 1, userId: 1, role: 2, content: "", status: 1, createdAt: "t" }
    ];
    // 第一次对账（流失败后自动触发）返回 STREAMING → 启动后台恢复。
    // 必须用 mockImplementationOnce：rid 在 send() 之后才被赋值，若提前调用 recoveringHistory()
    // 会捕获到初始值 "x"，导致 reconcile 找不到匹配的 USER 消息。
    listMessages.mockImplementationOnce(() => Promise.resolve(recoveringHistory()));
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    // 发送一个问题并等待流失败、对账完成。
    const composer = wrapper.findComponent(ChatComposer);
    composer.vm.$emit("update:modelValue", "question"); await flush();
    composer.vm.$emit("send"); await flush(); await flush(); await flush();
    // 后台恢复已启动 → SessionList 应收到 lockedSessionId=1。
    // 对账是多步异步（流失败 → markFailure → reconcile → fetchHistory），需等待。
    await vi.waitFor(() => expect(list.props("lockedSessionId")).toBe(1));
    // 尝试删除该会话 → 应被 removeSession 守卫拦住，不调 confirm 也不调 deleteSession。
    list.vm.$emit("delete", { id: 1, title: "A" }); await flush();
    expect(confirm).not.toHaveBeenCalled();
    expect(deleteSession).not.toHaveBeenCalled();
    expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining("暂不可删除"));
  });

  it("does not write a late history response after the page unmounts", async () => {
    let resolveHistory!: (value: any[]) => void;
    listMessages.mockImplementationOnce(() => new Promise(resolve => { resolveHistory = resolve; }));
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    wrapper.unmount();
    resolveHistory([{ id: 1, sessionId: 1, role: 1, content: "late", status: 2, userId: 1, createdAt: "t" }]);
    await flush();
    expect(wrapper.findComponent(MessageHistory).props("messages")).toEqual([]);
  });

  it("aborts the active stream before deleting its selected session", async () => {
    let signal!: AbortSignal;
    confirm.mockResolvedValue(undefined);
    deleteSession.mockResolvedValue(undefined);
    listMessages.mockResolvedValue([]);
    streamChat.mockImplementation((_request: unknown, options: { signal: AbortSignal }) => {
      signal = options.signal;
      return new Promise(() => {});
    });
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    const composer = wrapper.findComponent(ChatComposer);
    composer.vm.$emit("update:modelValue", "question"); await flush();
    composer.vm.$emit("send"); await flush();
    expect(signal.aborted).toBe(false);
    list.vm.$emit("delete", { id: 1, title: "A", knowledgeBaseId: 7 }); await flush();
    expect(signal.aborted).toBe(true);
  });

  it("aborts an active stream on component unmount", async () => {
    let signal!: AbortSignal;
    listMessages.mockResolvedValue([]);
    streamChat.mockImplementation((_request: unknown, options: { signal: AbortSignal }) => {
      signal = options.signal;
      return new Promise(() => {});
    });
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    const composer = wrapper.findComponent(ChatComposer);
    composer.vm.$emit("update:modelValue", "question"); await flush();
    composer.vm.$emit("send"); await flush();
    wrapper.unmount();
    expect(signal.aborted).toBe(true);
  });

  it("forwards the MessageHistory refresh event to listChatMessages without re-running selectSession", async () => {
    listMessages.mockResolvedValueOnce([{ id: 1, sessionId: 1, role: 1, content: "seed", status: 2, userId: 1, createdAt: "t" }]);
    const refreshPage = [{ id: 2, sessionId: 1, role: 2, content: "authoritative", status: 2, userId: 1, createdAt: "t" }];
    listMessages.mockResolvedValueOnce(refreshPage);
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    expect(listMessages).toHaveBeenCalledTimes(1);
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("seed");
    // 点击 MessageHistory 工具栏的"刷新"按钮 → 真实链路应直接请求 listChatMessages，
    // 而不是走 selectSession（否则会多一次清空 + 重新加载）。
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    expect(listMessages).toHaveBeenCalledTimes(2);
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("authoritative");
  });

  it("keeps existing messages when the manual refresh fails", async () => {
    listMessages.mockResolvedValueOnce([{ id: 1, sessionId: 1, role: 1, content: "seed", status: 2, userId: 1, createdAt: "t" }]);
    listMessages.mockRejectedValueOnce(new Error("network"));
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("seed");
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    expect(listMessages).toHaveBeenCalledTimes(2);
    // 失败时应保留当前内容，绝不能刷成空白。
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("seed");
    expect(messages.warning).toHaveBeenCalledWith("刷新消息失败，已保留当前内容。");
  });

  it("keeps session B refreshable while a session A refresh is still in flight", async () => {
    let resolveA!: (value: any[]) => void;
    // 调用顺序：select(1) → refresh A(挂起) → select(2) → refresh B。
    listMessages.mockResolvedValueOnce([{ id: 1, sessionId: 1, role: 1, content: "seed-1", status: 2, userId: 1, createdAt: "t" }])
      .mockImplementationOnce(() => new Promise(resolve => { resolveA = resolve; }))
      .mockResolvedValue([{ id: 2, sessionId: 2, role: 2, content: "B", status: 2, userId: 1, createdAt: "t" }]);
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    // 触发一次尚未完成的刷新，随后切到会话 B。
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    list.vm.$emit("select", 2); await flush();
    // 切走会使旧请求的 sequence 失效；旧请求必须释放锁（owner token），B 的刷新才能正常进行。
    resolveA([{ id: 9, sessionId: 1, role: 1, content: "stale-A", status: 2, userId: 1, createdAt: "t" }]); await flush();
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("B");
  });

  it("keeps B loading indicator while B history loads, despite a stale A refresh settling", async () => {
    let resolveA!: (value: any[]) => void;
    let resolveB!: (value: any[]) => void;
    let bCalls = 0;
    // select(1) 立即返回；A 手动刷新挂起；切到 B 后 B 的历史请求阻塞在 deferred。
    listMessages.mockResolvedValueOnce([{ id: 1, sessionId: 1, role: 1, content: "seed-1", status: 2, userId: 1, createdAt: "t" }])
      .mockImplementationOnce(() => new Promise(resolve => { resolveA = resolve; }))
      .mockImplementation(async () => { bCalls += 1; return new Promise(resolve => { resolveB = resolve; }); });
    const wrapper = mountPage(); await flush();
    const list = wrapper.findComponent(SessionList);
    list.vm.$emit("select", 1); await flush();
    // A 的刷新飞行中；此时再点一次刷新应进入 pending 队列。
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    // 切到 B → 应清理 A 的 pendingManualRefresh，且 B 的历史开始加载。
    list.vm.$emit("select", 2); await flush();
    expect(wrapper.findComponent(MessageHistory).props("loading")).toBe(true);
    // A 的旧刷新随后完成（stale）→ 绝不能关闭 B 的 loading，也不能触发补刷。
    resolveA([{ id: 9, sessionId: 1, role: 1, content: "stale-A", status: 2, userId: 1, createdAt: "t" }]); await flush();
    expect(wrapper.findComponent(MessageHistory).props("loading")).toBe(true);
    const callsWhenStillLoading = bCalls;
    // B 的历史终于返回 → loading 关闭，且只请求过一次 B 的历史（无多余补刷）。
    resolveB([{ id: 20, sessionId: 2, role: 2, content: "B", status: 2, userId: 1, createdAt: "t" }]);
    await vi.waitFor(() => expect(wrapper.findComponent(MessageHistory).props("loading")).toBe(false));
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("B");
    expect(bCalls).toBe(callsWhenStillLoading);
  });

  it("releases the refresh lock when the pending manual refresh resolves so a new refresh can run", async () => {
    let resolveRefresh!: (value: any[]) => void;
    // 依次：select(1) 立即返回；第一次手动刷新挂起；第二次手动刷新返回终态。
    // 注意：刷新期间 messageLoading=true，canSend 为 false，用户实际上无法在刷新未决时发起新流。
    // 本测试验证的是"挂起的刷新完成后必须释放 refreshInFlight 锁"，否则后续刷新会被
    // `if (refreshInFlight) { pendingManualRefresh = true; return; }` 拦住、永远无法执行。
    listMessages.mockResolvedValueOnce([])
      .mockImplementationOnce(() => new Promise(resolve => { resolveRefresh = resolve; }))
      .mockResolvedValue([{ id: 2, sessionId: 1, role: 2, content: "after-refresh", status: 2, userId: 1, createdAt: "t" }]);
    const wrapper = mountPage(); await flush();
    wrapper.findComponent(SessionList).vm.$emit("select", 1); await flush();
    // 触发一次尚未完成的刷新。
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    // 旧刷新尚未完成 → 此时再点刷新应进入 pending 队列（refreshInFlight 守卫）。
    wrapper.findComponent(MessageHistory).vm.$emit("refresh"); await flush();
    // 旧刷新完成 → 必须释放 refreshInFlight 锁，并回放 pendingManualRefresh。
    resolveRefresh([{ id: 99, sessionId: 1, role: 1, content: "stale", status: 2, userId: 1, createdAt: "t" }]); await flush();
    await flush();
    // 锁已释放且补刷已执行，最终消息应为第二次刷新的终态，而非旧刷新的 stale 数据。
    expect(wrapper.findComponent(MessageHistory).props("messages")[0].content).toBe("after-refresh");
  });

  it("uses the real v-auth directive to remove an element without ai:chat:list", () => {
    hasPermission.mockReturnValue(false);
    const parent = document.createElement("div");
    const restricted = document.createElement("button");
    parent.appendChild(restricted);
    (authDirective as any).mounted(restricted, { value: "ai:chat:list" }, {}, null);
    expect(hasPermission).toHaveBeenCalledWith("ai:chat:list");
    expect(parent.contains(restricted)).toBe(false);
  });
});
