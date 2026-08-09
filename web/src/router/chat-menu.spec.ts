import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("Chat menu seed SQL", () => {
  const sql = readFileSync(resolve(process.cwd(), "../database/bootstrap/040-chat-menus.sql"), "utf8");
  it("uses idempotent menu and permission insertion without routing button permissions", () => {
    expect(sql).toContain("INSERT IGNORE INTO sys_menu");
    expect(sql).toContain("WHERE NOT EXISTS");
    expect(sql).toContain("'AiChat', '/ai/chat', 'ai:chat:list', 0");
    expect(sql).toContain("'ai:chat:query', 1");
    expect(sql).not.toContain("'/ai/chat:query'");
    expect(sql).toContain("WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE router_name = 'AiChat')");
    expect(sql).not.toContain("router_name = 'AiChat' OR permission = 'ai:chat:list'");
  });
});
