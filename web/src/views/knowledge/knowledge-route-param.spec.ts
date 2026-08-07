import { describe, it, expect } from "vitest";

/**
 * 知识库路由参数验证逻辑测试。
 *
 * 验证：detail.vue 中的 knowledgeBaseId 计算属性
 * 能正确验证路由参数，拒绝无效输入。
 *
 * 这是 P1-6 的回归测试。
 */

// 复制 detail.vue 中的验证逻辑，独立测试
function validateKnowledgeBaseId(raw: unknown): number | null {
  const num = Number(raw);
  if (!Number.isInteger(num) || num <= 0 || !Number.isSafeInteger(num)) {
    return null;
  }
  return num;
}

describe("knowledge base id validation", () => {
  it("应接受有效的正整数 ID", () => {
    expect(validateKnowledgeBaseId("42")).toBe(42);
    expect(validateKnowledgeBaseId("1")).toBe(1);
    expect(validateKnowledgeBaseId("999999")).toBe(999999);
  });

  it("应拒绝 NaN（非数字字符串）", () => {
    expect(validateKnowledgeBaseId("abc")).toBeNull();
    expect(validateKnowledgeBaseId("")).toBeNull();
    expect(validateKnowledgeBaseId(":id")).toBeNull(); // 模板参数名
    expect(validateKnowledgeBaseId("null")).toBeNull();
    expect(validateKnowledgeBaseId("undefined")).toBeNull();
  });

  it("应拒绝负数", () => {
    expect(validateKnowledgeBaseId("-1")).toBeNull();
    expect(validateKnowledgeBaseId("-100")).toBeNull();
  });

  it("应拒绝零", () => {
    expect(validateKnowledgeBaseId("0")).toBeNull();
  });

  it("应拒绝小数", () => {
    expect(validateKnowledgeBaseId("1.5")).toBeNull();
    expect(validateKnowledgeBaseId("3.14")).toBeNull();
  });

  it("应拒绝非安全整数", () => {
    expect(validateKnowledgeBaseId("9007199254740992")).toBeNull(); // Number.MAX_SAFE_INTEGER + 1
  });

  it("应拒绝特殊值", () => {
    expect(validateKnowledgeBaseId("Infinity")).toBeNull();
    expect(validateKnowledgeBaseId("-Infinity")).toBeNull();
  });
});
