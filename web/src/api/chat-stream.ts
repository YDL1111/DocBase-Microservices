import { getAccessToken } from "@/utils/auth";
import { SseParser, SseProtocolError } from "@/utils/sse-parser";
import { refreshAccessTokenSingleFlight } from "@/utils/token-refresh";

const { VITE_APP_BASE_API } = import.meta.env;
const baseURL = VITE_APP_BASE_API || "";
const STREAM_PATH = "/api/ai/chat/stream";

export interface ChatStreamRequest {
  sessionId: number | null;
  knowledgeBaseIds: number[];
  question: string;
  clientRequestId: string;
}

/** Kept in the server's snake_case protocol shape for direct compatibility. */
export interface ChatSource {
  document_id: number;
  file_name?: string | null;
  page?: number | null;
  sheet?: string | null;
  slide?: number | null;
  heading_path?: string | null;
  block_type?: string | null;
  score?: number | null;
}

export type ChatStreamEvent =
  | { type: "session"; data: { sessionId: number; messageId: number } }
  | { type: "token"; data: string }
  | { type: "sources"; data: ChatSource[] }
  | { type: "done"; data: null }
  | { type: "error"; data: { code: string; message: string } };

export type ChatStreamOutcome =
  | { terminal: "done" }
  | { terminal: "error"; error: { code: string; message: string } };

export class ChatStreamClientError extends Error {
  constructor(
    readonly code:
      | "HTTP_ERROR"
      | "NETWORK_ERROR"
      | "INVALID_CONTENT_TYPE"
      | "EMPTY_STREAM"
      | "STREAM_INCOMPLETE"
      | "STREAM_PROTOCOL_ERROR"
      | "CLIENT_CANCELLED"
      | "UNAUTHENTICATED",
    readonly status?: number
  ) {
    super(code);
    this.name = "ChatStreamClientError";
  }
}

export interface StreamChatOptions {
  signal?: AbortSignal;
  onEvent: (event: ChatStreamEvent) => void | Promise<void>;
}

function isPositiveSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function protocolError(): ChatStreamClientError {
  return new ChatStreamClientError("STREAM_PROTOCOL_ERROR");
}

function toChatEvent(raw: string): ChatStreamEvent {
  let payload: unknown;
  try {
    payload = JSON.parse(raw);
  } catch {
    throw protocolError();
  }
  if (!payload || typeof payload !== "object") throw protocolError();
  const event = payload as { type?: unknown; data?: unknown };

  if (event.type === "session") {
    const data = event.data as { sessionId?: unknown; messageId?: unknown } | null;
    if (!data || !isPositiveSafeInteger(data.sessionId) || !isPositiveSafeInteger(data.messageId)) throw protocolError();
    return { type: "session", data: { sessionId: data.sessionId, messageId: data.messageId } };
  }
  if (event.type === "token") {
    if (typeof event.data !== "string") throw protocolError();
    return { type: "token", data: event.data };
  }
  if (event.type === "sources") {
    if (!Array.isArray(event.data) || !event.data.every(isChatSource)) throw protocolError();
    return { type: "sources", data: event.data as ChatSource[] };
  }
  if (event.type === "done") {
    if (event.data !== null) throw protocolError();
    return { type: "done", data: null };
  }
  if (event.type === "error") {
    const data = event.data as { code?: unknown; message?: unknown } | null;
    if (!data || typeof data.code !== "string" || typeof data.message !== "string") throw protocolError();
    return { type: "error", data: { code: data.code, message: data.message } };
  }
  throw protocolError();
}

function isChatSource(value: unknown): value is ChatSource {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const source = value as Record<string, unknown>;
  return isPositiveSafeInteger(source.document_id)
    && (source.file_name === undefined || source.file_name === null || typeof source.file_name === "string")
    && (source.page === undefined || source.page === null || isPositiveSafeInteger(source.page))
    && (source.sheet === undefined || source.sheet === null || typeof source.sheet === "string")
    && (source.slide === undefined || source.slide === null || isPositiveSafeInteger(source.slide))
    && (source.heading_path === undefined || source.heading_path === null || typeof source.heading_path === "string")
    && (source.block_type === undefined || source.block_type === null || typeof source.block_type === "string")
    && (source.score === undefined || source.score === null
      || (typeof source.score === "number" && Number.isFinite(source.score)));
}

function validateRequest(request: ChatStreamRequest): void {
  if (typeof request.question !== "string" || request.question.trim().length === 0 || request.question.length > 4000) {
    throw new RangeError("question must contain 1 to 4000 characters");
  }
  if (request.sessionId !== null && !isPositiveSafeInteger(request.sessionId)) throw new RangeError("sessionId must be a positive safe integer or null");
  if (!Array.isArray(request.knowledgeBaseIds) || request.knowledgeBaseIds.length > 20
    || request.knowledgeBaseIds.some(id => !isPositiveSafeInteger(id))
    || new Set(request.knowledgeBaseIds).size !== request.knowledgeBaseIds.length) {
    throw new RangeError("knowledgeBaseIds must contain at most 20 unique positive safe integers");
  }
  if (typeof request.clientRequestId !== "string" || request.clientRequestId.trim().length === 0) throw new RangeError("clientRequestId is required");
}

function cancelledError(): ChatStreamClientError {
  return new ChatStreamClientError("CLIENT_CANCELLED");
}

function isAbort(error: unknown, signal?: AbortSignal): boolean {
  return signal?.aborted === true || (error instanceof Error && error.name === "AbortError");
}

async function openStream(request: ChatStreamRequest, signal: AbortSignal | undefined, retried: boolean): Promise<Response> {
  if (signal?.aborted) throw cancelledError();
  const token = getAccessToken();
  if (!token) throw new ChatStreamClientError("UNAUTHENTICATED", 401);

  let response: Response;
  try {
    response = await fetch(`${baseURL}${STREAM_PATH}`, {
      method: "POST",
      signal,
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "text/event-stream",
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        sessionId: request.sessionId,
        knowledgeBaseIds: request.knowledgeBaseIds,
        question: request.question,
        clientRequestId: request.clientRequestId
      })
    });
  } catch (error) {
    if (isAbort(error, signal)) throw cancelledError();
    throw new ChatStreamClientError("NETWORK_ERROR");
  }

  if (response.status === 401 && !retried) {
    await cancelResponseBody(response);
    try {
      await refreshAccessTokenSingleFlight();
    } catch (error) {
      if (isAbort(error, signal)) throw cancelledError();
      throw new ChatStreamClientError("UNAUTHENTICATED", 401);
    }
    if (signal?.aborted) throw cancelledError();
    return openStream(request, signal, true);
  }
  if (!response.ok) {
    await cancelResponseBody(response);
    throw new ChatStreamClientError("HTTP_ERROR", response.status);
  }
  const contentType = response.headers.get("content-type")?.toLowerCase() || "";
  if (!contentType.startsWith("text/event-stream")) {
    await cancelResponseBody(response);
    throw new ChatStreamClientError("INVALID_CONTENT_TYPE", response.status);
  }
  if (!response.body) throw new ChatStreamClientError("EMPTY_STREAM", response.status);
  return response;
}

async function cancelResponseBody(response: Response): Promise<void> {
  try { await response.body?.cancel(); } catch { /* the typed response error remains authoritative */ }
}

async function cancelQuietly(reader: ReadableStreamDefaultReader<Uint8Array>): Promise<void> {
  try { await reader.cancel(); } catch { /* the original error remains authoritative */ }
}

export async function streamChat(request: ChatStreamRequest, options: StreamChatOptions): Promise<ChatStreamOutcome> {
  validateRequest(request);
  const response = await openStream(request, options.signal, false);
  const reader = response.body!.getReader();
  const decoder = new TextDecoder("utf-8");
  const parser = new SseParser();
  let receivedBytes = 0;
  let terminal: ChatStreamOutcome | null = null;

  const deliver = async (raw: string): Promise<void> => {
    if (terminal) return;
    let event: ChatStreamEvent;
    try {
      event = toChatEvent(raw);
    } catch (error) {
      if (error instanceof SseProtocolError) throw new ChatStreamClientError("STREAM_PROTOCOL_ERROR");
      throw error;
    }
    await options.onEvent(event);
    if (event.type === "done") terminal = { terminal: "done" };
    if (event.type === "error") terminal = { terminal: "error", error: event.data };
  };

  try {
    while (true) {
      if (options.signal?.aborted) throw cancelledError();
      let result: ReadableStreamReadResult<Uint8Array>;
      try {
        result = await reader.read();
      } catch (error) {
        if (isAbort(error, options.signal)) throw cancelledError();
        throw new ChatStreamClientError("NETWORK_ERROR");
      }
      if (result.done) break;
      receivedBytes += result.value.byteLength;
      if (!terminal) {
        for (const raw of parser.push(decoder.decode(result.value, { stream: true }))) await deliver(raw);
      }
    }
    if (!terminal) {
      for (const raw of parser.push(decoder.decode())) await deliver(raw);
      for (const raw of parser.finish()) await deliver(raw);
    }
    if (terminal) return terminal;
    throw new ChatStreamClientError(receivedBytes === 0 ? "EMPTY_STREAM" : "STREAM_INCOMPLETE");
  } catch (error) {
    await cancelQuietly(reader);
    if (error instanceof SseProtocolError) throw new ChatStreamClientError("STREAM_PROTOCOL_ERROR");
    throw error;
  } finally {
    reader.releaseLock();
  }
}
