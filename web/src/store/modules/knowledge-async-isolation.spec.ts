import { describe, it, expect, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useKnowledgeStore } from "./knowledge";

/**
 * 异步乱序响应隔离测试。
 *
 * 验证：当快速切换知识库时，先发出的请求（A 库）晚于后发出的请求（B 库）返回，
 * A 库的响应不应覆盖 B 库的数据。
 *
 * 这是 P0-2 的核心防护验证。
 */
describe("knowledge store - async response isolation", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  const makeFolder = (id: number, baseId: number) => ({
    id,
    parentId: 0,
    name: `base${baseId}-folder${id}`,
    sortNum: 0,
    children: []
  });

  const makeDoc = (id: number, baseId: number) => ({
    id,
    knowledgeBaseId: baseId,
    folderId: 0,
    title: `base${baseId}-doc${id}`,
    originalFilename: "test.pdf",
    objectKey: "key",
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

  const makeMember = (userId: number, baseId: number) => ({
    id: userId,
    knowledgeBaseId: baseId,
    userId,
    memberRole: 3,
    createdBy: 1,
    createdAt: "2026-01-01T00:00:00"
  });

  it("切换知识库后，旧请求的 setFolderTree 应被丢弃", () => {
    const store = useKnowledgeStore();

    // 进入知识库 A 上下文（seq=1）
    store.beginBaseContext(1);
    const seqA = store.getRequestSeq();

    // 切换到知识库 B（seq=2）
    store.beginBaseContext(2);
    const seqB = store.getRequestSeq();

    // A 库的迟到响应（携带 seq=1）
    store.setFolderTree([makeFolder(100, 1)], seqA);
    // 应被丢弃，folderTree 保持为空（切换 B 时已清空）
    expect(store.folderTree).toEqual([]);

    // B 库的正常响应（携带 seq=2）
    store.setFolderTree([makeFolder(200, 2)], seqB);
    expect(store.folderTree).toHaveLength(1);
    expect(store.folderTree[0].name).toBe("base2-folder200");
  });

  it("切换知识库后，旧请求的 setDocumentList 应被丢弃", () => {
    const store = useKnowledgeStore();

    store.beginBaseContext(1);
    const seqA = store.getRequestSeq();

    store.beginBaseContext(2);
    const seqB = store.getRequestSeq();

    // A 库迟到响应
    store.setDocumentList([makeDoc(100, 1)], 5, seqA);
    expect(store.documentList).toEqual([]);

    // B 库正常响应
    store.setDocumentList([makeDoc(200, 2)], 3, seqB);
    expect(store.documentList).toHaveLength(1);
    expect(store.documentList[0].title).toBe("base2-doc200");
  });

  it("切换知识库后，旧请求的 setMembers 应被丢弃", () => {
    const store = useKnowledgeStore();

    store.beginBaseContext(1);
    const seqA = store.getRequestSeq();

    store.beginBaseContext(2);
    const seqB = store.getRequestSeq();

    // A 库迟到响应
    store.setMembers([makeMember(99, 1)], seqA);
    expect(store.members).toEqual([]);

    // B 库正常响应
    store.setMembers([makeMember(88, 2)], seqB);
    expect(store.members).toHaveLength(1);
    expect(store.members[0].userId).toBe(88);
  });

  it("详情响应写入不递增序号，子请求可正常写入", () => {
    const store = useKnowledgeStore();

    // 进入知识库 1 上下文
    store.beginBaseContext(1);
    const seq = store.getRequestSeq();

    // 子组件捕获序号
    const childSeq = store.getRequestSeq();
    expect(childSeq).toBe(seq);

    // 详情响应写入（不递增序号）
    store.setCurrentBase(
      {
        id: 1,
        name: "知识库1",
        description: "",
        ownerId: 1,
        visibility: 3,
        status: 1,
        sortNum: 0,
        createdBy: 1,
        updatedBy: 1,
        createdAt: "",
        updatedAt: ""
      },
      seq
    );

    // 序号未变化
    expect(store.getRequestSeq()).toBe(seq);

    // 子请求仍可使用旧序号写入
    store.setFolderTree([makeFolder(1, 1)], childSeq);
    expect(store.folderTree).toHaveLength(1);

    store.setDocumentList([makeDoc(1, 1)], 1, childSeq);
    expect(store.documentList).toHaveLength(1);

    store.setMembers([makeMember(2, 1)], childSeq);
    expect(store.members).toHaveLength(1);
  });

  it("不传 seq 的 set 调用应直接写入（向后兼容）", () => {
    const store = useKnowledgeStore();
    store.beginBaseContext(1);

    // 不传 seq 时直接写入
    store.setFolderTree([makeFolder(1, 1)]);
    expect(store.folderTree).toHaveLength(1);

    store.setDocumentList([makeDoc(1, 1)], 1);
    expect(store.documentList).toHaveLength(1);

    store.setMembers([makeMember(2, 1)]);
    expect(store.members).toHaveLength(1);
  });

  it("reset 应递增 requestSeq", () => {
    const store = useKnowledgeStore();
    store.beginBaseContext(1);
    const seqAfterBegin = store.getRequestSeq();

    store.reset();
    expect(store.getRequestSeq()).toBe(seqAfterBegin + 1);
  });
});
