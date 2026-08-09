import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import authDirective from "@/directive/permission";

const { listSessions, listMessages, createSession, deleteSession, confirm, messages, hasPermission } = vi.hoisted(() => ({
  listSessions: vi.fn(), listMessages: vi.fn(), createSession: vi.fn(), deleteSession: vi.fn(), confirm: vi.fn(),
  messages: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }, hasPermission: vi.fn()
}));
vi.mock("@/api/chat", () => ({ listChatSessions: (...args: unknown[]) => listSessions(...args), listChatMessages: (...args: unknown[]) => listMessages(...args), createChatSession: (...args: unknown[]) => createSession(...args), deleteChatSession: (...args: unknown[]) => deleteSession(...args) }));
vi.mock("@/api/knowledge", () => ({ listKnowledgeBases: vi.fn() }));
vi.mock("@/utils/message", () => ({ message: messages }));
vi.mock("element-plus", () => ({ ElMessageBox: { confirm: (...args: unknown[]) => confirm(...args) } }));
vi.mock("@/store/modules/user", () => ({ useUserStoreHook: () => ({ hasPermission }) }));

import ChatPage from "./index.vue";

const SessionList = { name: "SessionList", props: ["sessions", "selectedSessionId", "loading", "deletingSessionId", "current", "size", "total"], emits: ["create", "select", "delete", "refresh", "pageChange", "sizeChange"], template: "<div><slot /></div>" };
const MessageHistory = { name: "MessageHistory", props: ["messages", "loading", "selectedSessionId"], template: "<div />" };
const CreateSessionDialog = { name: "CreateSessionDialog", props: ["modelValue", "knowledgeBases", "loadingKnowledgeBases", "creating"], emits: ["create", "opened", "update:modelValue"], template: "<div />" };
function result(records: any[] = []) { return { records, total: records.length, current: 1, size: 20, pages: 1 }; }
async function flush() { await nextTick(); await Promise.resolve(); await nextTick(); }
function mountPage() { return mount(ChatPage, { global: { stubs: { SessionList, MessageHistory, CreateSessionDialog }, directives: { auth: { mounted() {}, updated() {} } } } }); }

describe("chat history page", () => {
  beforeEach(() => { vi.clearAllMocks(); listSessions.mockResolvedValue(result([{ id: 1, title: "A", knowledgeBaseId: 7, updatedAt: "t", userId: 1, status: 1, createdAt: "t" }])); });

  it("renders sessions returned by the real page request", async () => {
    const wrapper = mountPage(); await flush();
    expect(wrapper.findComponent(SessionList).props("sessions")).toHaveLength(1);
    expect(listSessions).toHaveBeenCalledWith(1, 20);
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
