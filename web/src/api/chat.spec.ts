import { beforeEach, describe, expect, it, vi } from "vitest";

const { get, post, put, remove } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), remove: vi.fn() }));
vi.mock("@/utils/request", () => ({ http: { get, post, put, delete: remove } }));

import { createChatSession, deleteChatMessage, deleteChatSession, listChatMessages, listChatSessions, replaceChatSessionKnowledgeBases } from "./chat";

describe("chat API", () => {
  beforeEach(() => { get.mockReset(); post.mockReset(); put.mockReset(); remove.mockReset(); });

  it("replaces a session with zero, one or multiple deduplicated knowledge bases", () => {
    replaceChatSessionKnowledgeBases(9, [2, 3, 2]);
    expect(put).toHaveBeenCalledWith("/api/ai/chat/sessions/9/knowledge-bases", { knowledgeBaseIds: [2, 3] });
    replaceChatSessionKnowledgeBases(9, []);
    expect(put).toHaveBeenLastCalledWith("/api/ai/chat/sessions/9/knowledge-bases", { knowledgeBaseIds: [] });
  });

  it("uses the four Gateway paths and valid pagination", () => {
    listChatSessions(2, 20);
    createChatSession({ knowledgeBaseIds: [], title: "new" });
    listChatMessages(12);
    deleteChatSession(12);
    deleteChatMessage(12, 31);
    expect(get).toHaveBeenNthCalledWith(1, "/api/ai/chat/sessions", { params: { current: 2, size: 20 } });
    expect(post).toHaveBeenCalledWith("/api/ai/chat/sessions", { knowledgeBaseIds: [], title: "new" });
    expect(post.mock.calls[0][1]).not.toHaveProperty("userId");
    expect(get).toHaveBeenNthCalledWith(2, "/api/ai/chat/sessions/12/messages");
    expect(remove).toHaveBeenCalledWith("/api/ai/chat/sessions/12");
    expect(remove).toHaveBeenCalledWith("/api/ai/chat/sessions/12/messages/31");
  });

  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])("does not request invalid session id %s", invalid => {
    expect(() => listChatMessages(invalid)).toThrow(RangeError);
    expect(() => deleteChatSession(invalid)).toThrow(RangeError);
    expect(() => deleteChatMessage(1, invalid)).toThrow(RangeError);
    expect(() => deleteChatMessage(invalid, 1)).toThrow(RangeError);
    expect(get).not.toHaveBeenCalled();
    expect(remove).not.toHaveBeenCalled();
  });

  it("rejects invalid pagination before sending a request", () => {
    expect(() => listChatSessions(0, 20)).toThrow(RangeError);
    expect(() => listChatSessions(1, 101)).toThrow(RangeError);
    expect(get).not.toHaveBeenCalled();
  });
});
