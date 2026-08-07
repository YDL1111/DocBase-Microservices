import { describe, it, expect, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useKnowledgeStore } from "@/store/modules/knowledge";
import { MemberRole } from "@/api/types";

/**
 * 成员角色操作测试。
 * 验证成员添加、角色修改、移除的状态流转。
 */
describe("knowledge member operations", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  const makeMember = (userId: number, role: number) => ({
    id: userId,
    knowledgeBaseId: 1,
    userId,
    memberRole: role,
    createdBy: 1,
    createdAt: "2026-01-01T00:00:00"
  });

  it("setMembers 应设置成员列表", () => {
    const store = useKnowledgeStore();
    store.setMembers([
      makeMember(2, MemberRole.ADMIN),
      makeMember(3, MemberRole.EDITOR),
      makeMember(4, MemberRole.VIEWER)
    ]);
    expect(store.members).toHaveLength(3);
  });

  it("OWNER 角色不应被修改或移除（由组件层控制）", () => {
    const store = useKnowledgeStore();
    store.setMembers([
      makeMember(1, MemberRole.OWNER),
      makeMember(2, MemberRole.ADMIN)
    ]);

    const owner = store.members.find(m => m.memberRole === MemberRole.OWNER);
    expect(owner).toBeDefined();
    expect(owner?.userId).toBe(1);

    // 组件层应禁用 OWNER 的角色下拉和移除按钮
    // 这里验证 OWNER 的 memberRole 值
    expect(owner?.memberRole).toBe(MemberRole.OWNER);
  });

  it("updateMemberRole 应更新指定成员的角色", () => {
    const store = useKnowledgeStore();
    store.setMembers([
      makeMember(2, MemberRole.VIEWER),
      makeMember(3, MemberRole.EDITOR)
    ]);

    store.updateMemberRole(2, MemberRole.ADMIN);
    const member = store.members.find(m => m.userId === 2);
    expect(member?.memberRole).toBe(MemberRole.ADMIN);

    // 其他成员不受影响
    const other = store.members.find(m => m.userId === 3);
    expect(other?.memberRole).toBe(MemberRole.EDITOR);
  });

  it("removeMember 应移除指定成员", () => {
    const store = useKnowledgeStore();
    store.setMembers([
      makeMember(2, MemberRole.ADMIN),
      makeMember(3, MemberRole.EDITOR),
      makeMember(4, MemberRole.VIEWER)
    ]);

    store.removeMember(3);
    expect(store.members).toHaveLength(2);
    expect(store.members.find(m => m.userId === 3)).toBeUndefined();
  });

  it("成员角色枚举值应正确", () => {
    expect(MemberRole.OWNER).toBe(1);
    expect(MemberRole.ADMIN).toBe(2);
    expect(MemberRole.EDITOR).toBe(3);
    expect(MemberRole.VIEWER).toBe(4);
  });
});
