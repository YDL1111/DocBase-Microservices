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

/**
 * Recovery lifecycle for a single user intent. Phase 4C2A tracks one attempt at a
 * time so that "retry" can reuse the original `clientRequestId` while "edit and
 * resend" produces a fresh UUID. The attempt also drives history reconciliation
 * when the transport ends in an uncertain state.
 */
export const RecoveryStatus = {
  NONE: "none",
  UNCERTAIN: "uncertain",
  SYNCING: "syncing",
  RETRYABLE: "retryable",
  RECHECKING: "rechecking"
} as const;
export type RecoveryStatusKind = typeof RecoveryStatus[keyof typeof RecoveryStatus];

export interface RecoveryAttempt {
  sessionId: number;
  question: string;
  clientRequestId: string;
  generation: number;
  userMessageId: number | string;
  assistantMessageId: number | string;
  status: RecoveryStatusKind;
  /** Whether a "recheck" is already in flight, to coalesce repeated clicks. */
  pendingRecheck: boolean;
}

export interface ChatStreamState {
  streaming: boolean;
  draining: boolean;
  cancelling: boolean;
  canAcceptInput: boolean;
  /** True while a manual history refresh or a reconciliation is running. */
  syncing: boolean;
  attempt: RecoveryAttempt | null;
}

const SAFE_STATUS_TEXT: Record<RecoveryStatusKind, string> = {
  none: "",
  uncertain: "结果待确认，正在核对历史…",
  syncing: "正在核对历史…",
  retryable: "结果未完成，可重试",
  rechecking: "正在重新核对历史…"
};

export function recoveryStatusText(status: RecoveryStatusKind): string {
  return SAFE_STATUS_TEXT[status] ?? "";
}

/**
 * Maps a transport-level failure code to the appropriate recovery status.
 * Business failures that the server has already persisted as terminal are not
 * "uncertain" — only transport/incomplete outcomes and server-side 5xx need
 * reconciliation. Definitive client / permission errors (4xx) are hard failures.
 */
export function classifyRecovery(code: string, status?: number): "uncertain" | "failed" {
  if (code === "NETWORK_ERROR" || code === "STREAM_INCOMPLETE") return "uncertain";
  if (code === "HTTP_ERROR" && (status === undefined || status >= 500)) return "uncertain";
  return "failed";
}
