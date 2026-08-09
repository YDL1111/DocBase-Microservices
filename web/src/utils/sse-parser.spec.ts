import { describe, expect, it } from "vitest";
import { SseParser, SseProtocolError } from "./sse-parser";

describe("SseParser", () => {
  it("parses split, CRLF, comments, BOM, and multiple events", () => {
    const parser = new SseParser();
    expect(parser.push("\uFEFF:connected\r\n\r\ndata: one\r\n")).toEqual([]);
    expect(parser.push("data: two\r\n\r\ndata: three\n\n")).toEqual(["one\ntwo", "three"]);
  });

  it("handles an event and delimiter split across chunks", () => {
    const parser = new SseParser();
    expect(parser.push("data: {\"type\":\"token\",\"data\":\"")).toEqual([]);
    expect(parser.push("x\"}\n")).toEqual([]);
    expect(parser.push("\n")).toEqual(['{"type":"token","data":"x"}']);
  });

  it("emits a residual event on EOF", () => {
    const parser = new SseParser();
    parser.push("data: end");
    expect(parser.finish()).toEqual(["end"]);
  });

  it("bounds an unterminated event without exposing its contents", () => {
    const parser = new SseParser(8);
    expect(() => parser.push("data: 12345")).toThrow(SseProtocolError);
    try { parser.push("data: 12345"); } catch (error) {
      expect((error as SseProtocolError).code).toBe("SSE_BUFFER_LIMIT");
    }
  });
});
