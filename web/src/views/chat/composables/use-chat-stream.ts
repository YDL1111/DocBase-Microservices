import { ref, type Ref } from "vue";
import { ChatStreamClientError, streamChat, type ChatSource } from "@/api/chat-stream";
import { ChatMessageRole, ChatMessageStatus, type ChatMessage, type ChatSession } from "@/api/types";
import { message } from "@/utils/message";
import { toChatViewMessage, type ChatViewMessage } from "../chat-ui";

const MAX_QUESTION_LENGTH = 4000;

type CancelReason = "user" | "session-change" | "session-delete" | "route-leave" | "unmount" | "replacement" | "authentication";

interface StreamContext {
  generation: number;
  sessionId: number;
  controller: AbortController;
  assistant: ChatViewMessage;
  terminalReceived: boolean;
}

export interface UseChatStreamOptions {
  messages: Ref<ChatViewMessage[]>;
  selectedSessionId: Ref<number | null>;
  isMounted: () => boolean;
  invalidateHistory: () => void;
  fetchHistory: (sessionId: number) => Promise<ChatMessage[]>;
}

function requestId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  throw new Error("The browser does not support cryptographically secure request identifiers");
}

function timestamp(): string { return new Date().toISOString(); }

function safeFailureMessage(error: unknown): string {
  if (error instanceof ChatStreamClientError) {
    if (error.code === "STREAM_INCOMPLETE" || error.code === "EMPTY_STREAM") return "回答已中断，请稍后重试。";
    if (error.code === "CLIENT_CANCELLED") return "已停止生成。";
    if (error.code === "UNAUTHENTICATED") return "登录状态已失效，请重新登录。";
  }
  return "暂时无法生成回答，请稍后重试。";
}

export function useChatStream(options: UseChatStreamOptions) {
  const streaming = ref(false);
  const settling = ref(false);
  const draining = ref(false);
  const cancelling = ref(false);
  const canAcceptInput = ref(true);
  let generation = 0;
  let active: StreamContext | null = null;

  function isCurrent(context: StreamContext): boolean {
    return options.isMounted()
      && active === context
      && generation === context.generation
      && !context.controller.signal.aborted
      && options.selectedSessionId.value === context.sessionId;
  }

  function markFailure(context: StreamContext, code: string, notify: boolean): void {
    if (!isCurrent(context) || context.terminalReceived) return;
    context.terminalReceived = true;
    context.assistant.status = ChatMessageStatus.FAILED;
    context.assistant.errorCode = code;
    streaming.value = false;
    settling.value = true;
    draining.value = true;
    canAcceptInput.value = false;
    if (notify) message.error(safeFailureMessage(code === "STREAM_INCOMPLETE" ? new ChatStreamClientError("STREAM_INCOMPLETE") : undefined));
  }

  function cancel(reason: CancelReason): void {
    const context = active;
    if (!context) return;
    if (context.terminalReceived) {
      if (context.assistant.status === ChatMessageStatus.COMPLETED) {
        ++generation;
        active = null;
        settling.value = false;
        draining.value = false;
        cancelling.value = false;
        canAcceptInput.value = true;
      }
      return;
    }
    context.terminalReceived = true;
    streaming.value = false;
    settling.value = true;
    cancelling.value = true;
    canAcceptInput.value = false;
    if (reason === "user") {
      context.assistant.status = ChatMessageStatus.CANCELLED;
      context.assistant.errorCode = "CLIENT_CANCELLED";
      message.info("已停止生成。");
    }
    context.controller.abort();
  }

  async function complete(context: StreamContext): Promise<void> {
    if (!isCurrent(context)) return;
    if (!context.assistant.content.trim()) {
      markFailure(context, "EMPTY_RESPONSE", true);
      return;
    }
    context.terminalReceived = true;
    context.assistant.status = ChatMessageStatus.COMPLETED;
    streaming.value = false;
    settling.value = true;
    // The transport has ended successfully. History synchronization is replaceable,
    // so accepting another question here cannot interrupt a live server stream.
    canAcceptInput.value = true;
    try {
      const history = await options.fetchHistory(context.sessionId);
      if (!isCurrent(context)) return;
      options.messages.value = history.map(toChatViewMessage);
    } catch {
      // Keep the completed temporary message. The session can be refreshed manually.
    } finally {
      if (isCurrent(context)) {
        active = null;
        streaming.value = false;
        settling.value = false;
        draining.value = false;
        cancelling.value = false;
        canAcceptInput.value = true;
      }
    }
  }

  async function consume(context: StreamContext, request: { question: string; knowledgeBaseId: number; clientRequestId: string }): Promise<void> {
    try {
      const outcome = await streamChat({
        sessionId: context.sessionId,
        knowledgeBaseId: request.knowledgeBaseId,
        question: request.question,
        clientRequestId: request.clientRequestId
      }, {
        signal: context.controller.signal,
        onEvent: event => {
          if (!isCurrent(context) || context.terminalReceived) return;
          if (event.type === "session") {
            if (event.data.sessionId !== context.sessionId) {
              markFailure(context, "SESSION_MISMATCH", true);
              context.controller.abort();
            }
          } else if (event.type === "token") {
            context.assistant.content += event.data;
          } else if (event.type === "sources") {
            context.assistant.sources = event.data as ChatSource[];
          } else if (event.type === "error") {
            markFailure(context, event.data.code, true);
          }
        }
      });
      if (!isCurrent(context)) return;
      if (outcome.terminal === "done") await complete(context);
      else markFailure(context, outcome.error.code, true);
    } catch (error) {
      if (!isCurrent(context)) return;
      if (error instanceof ChatStreamClientError && error.code === "CLIENT_CANCELLED") return;
      const code = error instanceof ChatStreamClientError
        ? error.code
        : typeof error === "object" && error !== null && typeof (error as { code?: unknown }).code === "string"
          ? (error as { code: string }).code
          : "STREAM_ERROR";
      markFailure(context, code, true);
    } finally {
      if (active === context && generation === context.generation && context.terminalReceived && context.assistant.status !== ChatMessageStatus.COMPLETED) {
        active = null;
        streaming.value = false;
        settling.value = false;
        draining.value = false;
        cancelling.value = false;
        canAcceptInput.value = true;
      }
    }
  }

  function send(question: string, session: ChatSession | undefined): boolean {
    const normalizedQuestion = question.trim();
    if (!canAcceptInput.value || streaming.value || draining.value || cancelling.value || !normalizedQuestion || normalizedQuestion.length > MAX_QUESTION_LENGTH) return false;
    if (!session || !Number.isSafeInteger(session.id) || session.id < 1 || !Number.isSafeInteger(session.knowledgeBaseId) || (session.knowledgeBaseId ?? 0) < 1) {
      message.warning("请选择一个已绑定知识库的有效会话后再提问。");
      return false;
    }
    const knowledgeBaseId = session.knowledgeBaseId as number;
    let clientRequestId: string;
    try { clientRequestId = requestId(); } catch { message.error("当前浏览器无法安全创建请求，请升级浏览器后重试。"); return false; }
    cancel("replacement");
    options.invalidateHistory();
    const context: StreamContext = {
      generation: ++generation,
      sessionId: session.id,
      controller: new AbortController(),
      terminalReceived: false,
      assistant: {
        id: `stream-assistant-${generation}`,
        sessionId: session.id,
        userId: session.userId,
        role: ChatMessageRole.ASSISTANT,
        content: "",
        status: ChatMessageStatus.STREAMING,
        createdAt: timestamp(),
        sources: [],
        temporary: true
      }
    };
    active = context;
    streaming.value = true;
    canAcceptInput.value = false;
    options.messages.value.push(
      {
        id: `stream-user-${context.generation}`,
        sessionId: session.id,
        userId: session.userId,
        role: ChatMessageRole.USER,
        content: normalizedQuestion,
        status: ChatMessageStatus.COMPLETED,
        createdAt: timestamp(),
        clientRequestId,
        temporary: true
      },
      context.assistant
    );
    void consume(context, { question: normalizedQuestion, knowledgeBaseId, clientRequestId });
    return true;
  }

  return { streaming, settling, draining, cancelling, canAcceptInput, send, cancel };
}
