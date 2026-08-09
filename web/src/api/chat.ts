import { http } from "@/utils/request";
import type { ChatMessage, ChatSession, CreateChatSessionRequest, PageResult } from "./types";

const MAX_PAGE_SIZE = 100;

function positiveSafeInteger(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 1) throw new RangeError(`${field} must be a positive safe integer`);
}

export function listChatSessions(current = 1, size = 20): Promise<PageResult<ChatSession>> {
  positiveSafeInteger(current, "current");
  positiveSafeInteger(size, "size");
  if (size > MAX_PAGE_SIZE) throw new RangeError(`size must not exceed ${MAX_PAGE_SIZE}`);
  return http.get<PageResult<ChatSession>>("/api/ai/chat/sessions", { params: { current, size } });
}

export function createChatSession(request: CreateChatSessionRequest): Promise<ChatSession> {
  if (request.knowledgeBaseId !== null) positiveSafeInteger(request.knowledgeBaseId, "knowledgeBaseId");
  if (request.title.length > 255) throw new RangeError("title must not exceed 255 characters");
  return http.post<ChatSession>("/api/ai/chat/sessions", {
    knowledgeBaseId: request.knowledgeBaseId,
    title: request.title
  });
}

export function listChatMessages(sessionId: number): Promise<ChatMessage[]> {
  positiveSafeInteger(sessionId, "sessionId");
  return http.get<ChatMessage[]>(`/api/ai/chat/sessions/${sessionId}/messages`);
}

export function deleteChatSession(sessionId: number): Promise<void> {
  positiveSafeInteger(sessionId, "sessionId");
  return http.delete<void>(`/api/ai/chat/sessions/${sessionId}`);
}
