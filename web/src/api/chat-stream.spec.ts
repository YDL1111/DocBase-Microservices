import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import axios from "axios";
import { streamChat, type ChatStreamEvent, ChatStreamClientError } from "./chat-stream";
import { __resetTokenRefreshForTests } from "@/utils/token-refresh";

const encoder = new TextEncoder();
const request = { sessionId: 1, knowledgeBaseIds: [] as number[], question: "question", clientRequestId: "request-id" };

function response(chunks: string[], options: { status?: number; contentType?: string; body?: ReadableStream<Uint8Array> | null } = {}): Response {
  const body = options.body === undefined
    ? new ReadableStream<Uint8Array>({ start(controller) { chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk))); controller.close(); } })
    : options.body;
  return new Response(body, {
    status: options.status ?? 200,
    headers: { "content-type": options.contentType ?? "text/event-stream; charset=utf-8" }
  });
}

describe("streamChat", () => {
  beforeEach(() => {
    sessionStorage.clear();
    sessionStorage.setItem("docbase_access_token", "access-value");
    __resetTokenRefreshForTests();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it("uses POST fetch, exact safe JSON fields, and incrementally delivers typed events", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response([
      'data: {"type":"session","data":{"sessionId":1,',
      '"messageId":2}}\n\ndata: {"type":"token","data":"你',
      '好"}\n\ndata: {"type":"sources","data":[{"document_id":1,"file_name":"x.pdf","page":3}]}\n\ndata: {"type":"done","data":null}\n\n'
    ]));
    vi.stubGlobal("fetch", fetchMock);
    const events: ChatStreamEvent[] = [];
    await expect(streamChat(request, { onEvent: event => { events.push(event); } })).resolves.toEqual({ terminal: "done" });
    expect(fetchMock).toHaveBeenCalledWith("/api/ai/chat/stream", expect.objectContaining({
      method: "POST",
      headers: expect.objectContaining({ Authorization: "Bearer access-value", Accept: "text/event-stream" })
    }));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual(request);
    expect(events.map(event => event.type)).toEqual(["session", "token", "sources", "done"]);
    expect(events[2]).toEqual({ type: "sources", data: [{ document_id: 1, file_name: "x.pdf", page: 3 }] });
  });

  it("allows a new general chat request without a knowledge base", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(['data: {"type":"done","data":null}\n\n']));
    vi.stubGlobal("fetch", fetchMock);
    const generalRequest = { ...request, sessionId: null, knowledgeBaseIds: [] };
    await expect(streamChat(generalRequest, { onEvent: vi.fn() })).resolves.toEqual({ terminal: "done" });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual(generalRequest);
  });

  it("preserves a UTF-8 character split across real Uint8Array chunks", async () => {
    const bytes = encoder.encode('data: {"type":"token","data":"你"}\n\ndata: {"type":"done","data":null}\n\n');
    const characterStart = bytes.findIndex((value, index) => value === 0xe4 && bytes[index + 1] === 0xbd);
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes.slice(0, characterStart + 1));
        controller.enqueue(bytes.slice(characterStart + 1));
        controller.close();
      }
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { body })));
    const events: ChatStreamEvent[] = [];
    await streamChat(request, { onEvent: event => { events.push(event); } });
    expect(events).toContainEqual({ type: "token", data: "你" });
  });

  it("accepts a UTF-8 BOM split across actual byte chunks", async () => {
    const bytes = encoder.encode('\uFEFFdata: {"type":"done","data":null}\n\n');
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes.slice(0, 1));
        controller.enqueue(bytes.slice(1, 2));
        controller.enqueue(bytes.slice(2));
        controller.close();
      }
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { body })));
    await expect(streamChat(request, { onEvent: vi.fn() })).resolves.toEqual({ terminal: "done" });
  });

  it("ignores events after terminal while reading through EOF", async () => {
    let cancelled = false;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"type":"done","data":null}\n\ndata: {"type":"token","data":"late"}\n\n'));
        controller.close();
      },
      cancel() { cancelled = true; }
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { body })));
    const events: ChatStreamEvent[] = [];
    await streamChat(request, { onEvent: event => { events.push(event); } });
    expect(events).toEqual([{ type: "done", data: null }]);
    expect(cancelled).toBe(false);
  });

  it.each([400, 403, 500])("does not retry HTTP %i", async status => {
    const fetchMock = vi.fn().mockResolvedValue(response([], { status }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "HTTP_ERROR", status });
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("cancels rejected HTTP and non-SSE response bodies before returning typed errors", async () => {
    let httpCancelled = false;
    const httpBody = new ReadableStream<Uint8Array>({ cancel() { httpCancelled = true; } });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { status: 500, body: httpBody })));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "HTTP_ERROR", status: 500 });
    expect(httpCancelled).toBe(true);

    let typeCancelled = false;
    const typeBody = new ReadableStream<Uint8Array>({ cancel() { typeCancelled = true; } });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { contentType: "application/json", body: typeBody })));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "INVALID_CONTENT_TYPE" });
    expect(typeCancelled).toBe(true);
  });

  it("reports invalid response, empty response, and incomplete EOF", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { contentType: "application/json" })));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "INVALID_CONTENT_TYPE" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { body: null })));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "EMPTY_STREAM" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(['data: {"type":"token","data":"x"}\n\n'])));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "STREAM_INCOMPLETE" });
  });

  it("returns a typed protocol error for malformed payloads", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(["data: {bad}\n\n"])));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "STREAM_PROTOCOL_ERROR" });
  });

  it("accepts nullable Source fields emitted by the backend", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([
      'data: {"type":"sources","data":[{"document_id":1,"file_name":"x.txt","page":null},{"document_id":2,"file_name":null,"page":null}]}\n\n',
      'data: {"type":"done","data":null}\n\n'
    ])));
    const events: ChatStreamEvent[] = [];
    await expect(streamChat(request, { onEvent: event => { events.push(event); } })).resolves.toEqual({ terminal: "done" });
    expect(events[0]).toEqual({ type: "sources", data: [
      { document_id: 1, file_name: "x.txt", page: null },
      { document_id: 2, file_name: null, page: null }
    ] });
  });

  it("rejects malformed source item fields as a protocol error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([
      'data: {"type":"sources","data":[{"document_id":"not-a-number"}]}\n\n'
    ])));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "STREAM_PROTOCOL_ERROR" });
  });

  it("refreshes once after an initial 401 and reuses the exact request", async () => {
    sessionStorage.setItem("docbase_refresh_token", "refresh-value");
    vi.spyOn(axios, "post").mockResolvedValue({ data: { success: true, data: { accessToken: "new-access", refreshToken: "new-refresh" } } } as any);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response([], { status: 401 }))
      .mockResolvedValueOnce(response(['data: {"type":"done","data":null}\n\n']));
    vi.stubGlobal("fetch", fetchMock);
    await streamChat(request, { onEvent: vi.fn() });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe("Bearer new-access");
    expect(fetchMock.mock.calls[1][1].body).toBe(fetchMock.mock.calls[0][1].body);
  });

  it("shares one refresh across concurrent SSE 401 responses", async () => {
    sessionStorage.setItem("docbase_refresh_token", "refresh-value");
    const refresh = vi.spyOn(axios, "post").mockResolvedValue({
      data: { success: true, data: { accessToken: "new-access", refreshToken: "new-refresh" } }
    } as any);
    let calls = 0;
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => {
      calls += 1;
      return Promise.resolve(calls <= 2
        ? response([], { status: 401 })
        : response(['data: {"type":"done","data":null}\n\n']));
    }));
    await Promise.all([streamChat(request, { onEvent: vi.fn() }), streamChat(request, { onEvent: vi.fn() })]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("does not loop after a second 401", async () => {
    sessionStorage.setItem("docbase_refresh_token", "refresh-value");
    const refresh = vi.spyOn(axios, "post").mockResolvedValue({
      data: { success: true, data: { accessToken: "new-access", refreshToken: "new-refresh" } }
    } as any);
    const fetchMock = vi.fn().mockResolvedValue(response([], { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "HTTP_ERROR", status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("does not let one cancelled SSE caller cancel the shared refresh", async () => {
    sessionStorage.setItem("docbase_refresh_token", "refresh-value");
    let resolveRefresh!: (value: unknown) => void;
    const refresh = vi.spyOn(axios, "post").mockImplementation(() => new Promise(resolve => { resolveRefresh = resolve; }) as any);
    let calls = 0;
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => {
      calls += 1;
      return Promise.resolve(calls <= 2 ? response([], { status: 401 }) : response(['data: {"type":"done","data":null}\n\n']));
    }));
    const controller = new AbortController();
    const cancelled = streamChat(request, { signal: controller.signal, onEvent: vi.fn() });
    const completed = streamChat(request, { onEvent: vi.fn() });
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    controller.abort();
    resolveRefresh({ data: { success: true, data: { accessToken: "new-access", refreshToken: "new-refresh" } } });
    await expect(cancelled).rejects.toMatchObject({ code: "CLIENT_CANCELLED" });
    await expect(completed).resolves.toEqual({ terminal: "done" });
  });

  it("maps refresh failure to UNAUTHENTICATED", async () => {
    sessionStorage.setItem("docbase_refresh_token", "refresh-value");
    vi.spyOn(axios, "post").mockRejectedValue(new Error("refresh failed"));
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { status: 401 })));
    await expect(streamChat(request, { onEvent: vi.fn() })).rejects.toMatchObject({ code: "UNAUTHENTICATED", status: 401 });
  });

  it("cancels and releases the reader when onEvent throws", async () => {
    let cancelled = false;
    const body = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(encoder.encode('data: {"type":"token","data":"x"}\n\n')); },
      cancel() { cancelled = true; }
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response([], { body })));
    await expect(streamChat(request, { onEvent: () => { throw new Error("callback failed"); } })).rejects.toThrow("callback failed");
    expect(cancelled).toBe(true);
  });

  it("propagates explicit cancellation distinctly and does not make a request", async () => {
    const controller = new AbortController();
    controller.abort();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(streamChat(request, { signal: controller.signal, onEvent: vi.fn() })).rejects.toMatchObject({ code: "CLIENT_CANCELLED" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
