import type { ChatMessage } from "@/api/types";
import type { ChatSource } from "@/api/chat-stream";

export interface ChatViewMessage {
  id: number | string;
  sessionId: number;
  userId: number;
  role: number;
  content: string;
  status: number;
  createdAt: string;
  completedAt?: string | null;
  clientRequestId?: string | null;
  errorCode?: string | null;
  sourcesJson?: string | null;
  sources?: ChatSource[];
  temporary?: boolean;
}

export function toChatViewMessage(message: ChatMessage): ChatViewMessage {
  return { ...message };
}
