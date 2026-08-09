export const MAX_SSE_EVENT_BYTES = 1024 * 1024;

export class SseProtocolError extends Error {
  readonly code: "STREAM_PROTOCOL_ERROR" | "SSE_BUFFER_LIMIT";
  constructor(code: "STREAM_PROTOCOL_ERROR" | "SSE_BUFFER_LIMIT", message: string) {
    super(message);
    this.name = "SseProtocolError";
    this.code = code;
  }
}

/** Incremental SSE text parser; JSON validation belongs to the stream transport. */
export class SseParser {
  private buffer = "";
  private dataLines: string[] = [];
  private readonly encoder = new TextEncoder();
  private bomAllowed = true;

  constructor(private readonly maximumEventBytes = MAX_SSE_EVENT_BYTES) {}

  push(text: string): string[] {
    if (this.bomAllowed && text.length > 0) {
      this.bomAllowed = false;
      if (text.startsWith("\uFEFF")) text = text.slice(1);
    }
    this.buffer += text;
    this.ensureBounded();
    const events: string[] = [];
    let newlineIndex = this.buffer.indexOf("\n");
    while (newlineIndex !== -1) {
      const line = this.buffer.slice(0, newlineIndex).replace(/\r$/, "");
      this.buffer = this.buffer.slice(newlineIndex + 1);
      this.consumeLine(line, events);
      newlineIndex = this.buffer.indexOf("\n");
    }
    this.ensureBounded();
    return events;
  }

  finish(): string[] {
    const events: string[] = [];
    if (this.buffer.length > 0) {
      this.consumeLine(this.buffer.replace(/\r$/, ""), events);
      this.buffer = "";
    }
    this.dispatch(events);
    return events;
  }

  private consumeLine(line: string, events: string[]): void {
    if (line === "") { this.dispatch(events); return; }
    if (line.startsWith(":")) return;
    const separator = line.indexOf(":");
    const field = separator === -1 ? line : line.slice(0, separator);
    let value = separator === -1 ? "" : line.slice(separator + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "data") {
      this.dataLines.push(value);
      this.ensureBounded();
    }
  }

  private dispatch(events: string[]): void {
    if (this.dataLines.length) events.push(this.dataLines.join("\n"));
    this.dataLines = [];
  }

  private ensureBounded(): void {
    const pending = this.dataLines.length ? `${this.dataLines.join("\n")}\n${this.buffer}` : this.buffer;
    if (this.encoder.encode(pending).byteLength > this.maximumEventBytes) {
      throw new SseProtocolError("SSE_BUFFER_LIMIT", "SSE event exceeds the allowed buffer size");
    }
  }
}
