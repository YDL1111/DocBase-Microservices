import { describe, expect, it } from "vitest";
import { formatBackendDateTime } from "./date-time";

describe("formatBackendDateTime", () => {
  it("treats offset-less backend timestamps as UTC and renders Beijing time", () => {
    expect(formatBackendDateTime("2026-08-20T02:28:22")).toBe("2026-08-20 10:28:22");
  });

  it("keeps explicit offsets authoritative and handles empty or invalid values safely", () => {
    expect(formatBackendDateTime("2026-08-20T10:28:22+08:00")).toBe("2026-08-20 10:28:22");
    expect(formatBackendDateTime(null)).toBe("—");
    expect(formatBackendDateTime("not-a-date")).toBe("not-a-date");
  });
});
