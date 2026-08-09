import { beforeEach, describe, expect, it, vi } from "vitest";

const { get, post, remove } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), remove: vi.fn() }));
vi.mock("@/utils/request", () => ({ http: { get, post, delete: remove } }));

import { createChatSession, deleteChatSession, listChatMessages, listChatSessions } from "./chat";

describe("chat API", () => {
  beforeEach(() => { get.mockReset(); post.mockReset(); remove.mockReset(); });

  it("uses the four Gateway paths and valid pagination", () => {
    listChatSessions(2, 20);
    createChatSession({ knowledgeBaseId: null, title: "new" });
    listChatMessages(12);
    deleteChatSession(12);
    expect(get).toHaveBeenNthCalledWith(1, "/api/ai/chat/sessions", { params: { current: 2, size: 20 } });
    expect(post).toHaveBeenCalledWith("/api/ai/chat/sessions", { knowledgeBaseId: null, title: "new" });
    expect(post.mock.calls[0][1]).not.toHaveProperty("userId");
    expect(get).toHaveBeenNthCalledWith(2, "/api/ai/chat/sessions/12/messages");
    expect(remove).toHaveBeenCalledWith("/api/ai/chat/sessions/12");
  });

  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])("does not request invalid session id %s", invalid => {
    expect(() => listChatMessages(invalid)).toThrow(RangeError);
    expect(() => deleteChatSession(invalid)).toThrow(RangeError);
    expect(get).not.toHaveBeenCalled();
    expect(remove).not.toHaveBeenCalled();
  });

  it("rejects invalid pagination before sending a request", () => {
    expect(() => listChatSessions(0, 20)).toThrow(RangeError);
    expect(() => listChatSessions(1, 101)).toThrow(RangeError);
    expect(get).not.toHaveBeenCalled();
  });
});
