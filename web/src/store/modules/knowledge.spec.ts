import { describe, it, expect, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useKnowledgeStore } from "./knowledge";
import type {
  KnowledgeBase,
  FolderNode,
  KnowledgeDocument,
  KnowledgeMember
} from "@/api/types";

describe("knowledge store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  const makeBase = (id: number): KnowledgeBase => ({
    id,
    name: `知识库${id}`,
    description: `描述${id}`,
    ownerId: 1,
    visibility: 3,
    status: 1,
    sortNum: 0,
    createdBy: 1,
    updatedBy: 1,
    createdAt: "2026-01-01T00:00:00",
    updatedAt: "2026-01-01T00:00:00"
  });

  const makeFolder = (id: number): FolderNode => ({
    id,
    parentId: 0,
    name: `目录${id}`,
    sortNum: 0,
    children: []
  });

  const makeDoc = (id: number): KnowledgeDocument => ({
    id,
    knowledgeBaseId: 1,
    folderId: 0,
    title: `文档${id}`,
    originalFilename: `file${id}.pdf`,
    objectKey: `key${id}`,
    contentType: "application/pdf",
    fileSize: 1024,
    checksum: "abc",
    ingestStatus: 3,
    version: 1,
    status: 2,
    visibility: 3,
    createdBy: 1,
    updatedBy: 1,
    createdAt: "2026-01-01T00:00:00",
    updatedAt: "2026-01-01T00:00:00"
  });

  const makeMember = (userId: number): KnowledgeMember => ({
    id: userId,
    knowledgeBaseId: 1,
    userId,
    memberRole: userId === 1 ? 1 : 3,
    createdBy: 1,
    createdAt: "2026-01-01T00:00:00"
  });

  it("初始状态应为空", () => {
    const store = useKnowledgeStore();
    expect(store.baseList).toEqual([]);
    expect(store.currentBase).toBeNull();
    expect(store.folderTree).toEqual([]);
    expect(store.documentList).toEqual([]);
    expect(store.members).toEqual([]);
    expect(store.currentFolderId).toBe(0);
  });

  it("setBaseList 应设置列表和总数", () => {
    const store = useKnowledgeStore();
    const list = [makeBase(1), makeBase(2)];
    store.setBaseList(list, 10);
    expect(store.baseList).toHaveLength(2);
    expect(store.total).toBe(10);
  });

  it("切换知识库时应重置子状态（防止状态串库）", () => {
    const store = useKnowledgeStore();

    // 进入知识库 1 的上下文
    store.beginBaseContext(1);
    store.setCurrentBase(makeBase(1));
    store.setFolderTree([makeFolder(1), makeFolder(2)]);
    store.setDocumentList([makeDoc(1)], 1);
    store.setMembers([makeMember(2)]);
    store.setCurrentFolderId(5);

    expect(store.folderTree).toHaveLength(2);
    expect(store.documentList).toHaveLength(1);
    expect(store.members).toHaveLength(1);
    expect(store.currentFolderId).toBe(5);

    // 切换到知识库 2 —— 调用 beginBaseContext 清空子状态
    store.beginBaseContext(2);

    expect(store.currentBase).toBeNull();
    expect(store.folderTree).toEqual([]);
    expect(store.documentList).toEqual([]);
    expect(store.members).toEqual([]);
    expect(store.currentFolderId).toBe(0);
    // 注意：baseList 不清空（列表是独立的）
  });

  it("setCurrentBase 带 seq 校验时不应递增序号", () => {
    const store = useKnowledgeStore();
    store.beginBaseContext(1);
    const seq = store.getRequestSeq();

    // 详情响应写入（不递增序号）
    store.setCurrentBase(makeBase(1), seq);
    expect(store.getRequestSeq()).toBe(seq);
    expect(store.currentBase?.id).toBe(1);
  });

  it("切换到相同知识库时 beginBaseContext 不应递增序号", () => {
    const store = useKnowledgeStore();
    store.beginBaseContext(1);
    const seq = store.getRequestSeq();

    // 再次进入同一知识库（不应递增）
    store.beginBaseContext(1);
    expect(store.getRequestSeq()).toBe(seq);
  });

  it("setFolderTree 应设置目录树", () => {
    const store = useKnowledgeStore();
    const tree = [makeFolder(1)];
    store.setFolderTree(tree);
    expect(store.folderTree).toEqual(tree);
  });

  it("setDocumentList 应设置文档列表和总数", () => {
    const store = useKnowledgeStore();
    store.setDocumentList([makeDoc(1), makeDoc(2)], 20);
    expect(store.documentList).toHaveLength(2);
    expect(store.documentTotal).toBe(20);
  });

  it("setMembers / removeMember / updateMemberRole 应正确更新成员", () => {
    const store = useKnowledgeStore();
    store.setMembers([makeMember(2), makeMember(3)]);

    // 移除成员
    store.removeMember(2);
    expect(store.members).toHaveLength(1);
    expect(store.members[0].userId).toBe(3);

    // 更新角色
    store.updateMemberRole(3, 2);
    expect(store.members[0].memberRole).toBe(2);
  });

  it("reset 应清空所有状态", () => {
    const store = useKnowledgeStore();
    store.setBaseList([makeBase(1)], 1);
    store.setCurrentBase(makeBase(1));
    store.setFolderTree([makeFolder(1)]);
    store.setDocumentList([makeDoc(1)], 1);
    store.setMembers([makeMember(2)]);

    store.reset();

    expect(store.baseList).toEqual([]);
    expect(store.currentBase).toBeNull();
    expect(store.folderTree).toEqual([]);
    expect(store.documentList).toEqual([]);
    expect(store.members).toEqual([]);
  });

  it("currentBaseId getter 应返回当前知识库 ID", () => {
    const store = useKnowledgeStore();
    expect(store.currentBaseId).toBeNull();
    store.setCurrentBase(makeBase(42));
    expect(store.currentBaseId).toBe(42);
  });
});
