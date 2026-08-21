import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  listKnowledgeBases,
  getKnowledgeBase,
  createKnowledgeBase,
  updateKnowledgeBase,
  deleteKnowledgeBase,
  getFolderTree,
  createFolder,
  updateFolder,
  deleteFolder,
  listDocuments,
  getDocument,
  getDocumentContent,
  updateDocument,
  reingestDocument,
  deleteDocument,
  listMembers,
  addMember,
  updateMemberRole,
  removeMember
} from "./knowledge";

// 模拟 request 层
vi.mock("@/utils/request", () => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}));

import { http } from "@/utils/request";

describe("knowledge api", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("知识库", () => {
    it("listKnowledgeBases 应 GET /api/knowledge/bases 并携带分页参数", async () => {
      (http.get as any).mockResolvedValue({ records: [], total: 0 });
      await listKnowledgeBases({ current: 1, size: 20 });
      expect(http.get).toHaveBeenCalledWith("/api/knowledge/bases", {
        params: { current: 1, size: 20 }
      });
    });

    it("getKnowledgeBase 应 GET /api/knowledge/bases/{id}", async () => {
      (http.get as any).mockResolvedValue({});
      await getKnowledgeBase(42);
      expect(http.get).toHaveBeenCalledWith("/api/knowledge/bases/42");
    });

    it("createKnowledgeBase 应 POST /api/knowledge/bases 并传 name/description/visibility", async () => {
      (http.post as any).mockResolvedValue(1);
      await createKnowledgeBase({
        name: "测试知识库",
        description: "描述",
        visibility: 3
      });
      expect(http.post).toHaveBeenCalledWith("/api/knowledge/bases", {
        name: "测试知识库",
        description: "描述",
        visibility: 3
      });
    });

    it("updateKnowledgeBase 应 PUT /api/knowledge/bases/{id}", async () => {
      (http.put as any).mockResolvedValue(undefined);
      await updateKnowledgeBase(42, { name: "新名称" });
      expect(http.put).toHaveBeenCalledWith("/api/knowledge/bases/42", {
        name: "新名称"
      });
    });

    it("deleteKnowledgeBase 应 DELETE /api/knowledge/bases/{id}", async () => {
      (http.delete as any).mockResolvedValue(undefined);
      await deleteKnowledgeBase(42);
      expect(http.delete).toHaveBeenCalledWith("/api/knowledge/bases/42");
    });
  });

  describe("目录", () => {
    it("getFolderTree 应 GET /api/knowledge/bases/{kbId}/folders/tree", async () => {
      (http.get as any).mockResolvedValue([]);
      await getFolderTree(42);
      expect(http.get).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/folders/tree"
      );
    });

    it("createFolder 应 POST 并携带 knowledgeBaseId 在路径中", async () => {
      (http.post as any).mockResolvedValue(10);
      await createFolder(42, { parentId: 0, name: "新目录", sortNum: 1 });
      expect(http.post).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/folders",
        { parentId: 0, name: "新目录", sortNum: 1 }
      );
      // knowledgeBaseId 必须在路径中，不应出现在 body 中
      const call = (http.post as any).mock.calls[0];
      expect(call[0]).toContain("/api/knowledge/bases/42/folders");
    });

    it("updateFolder 应 PUT 并携带 knowledgeBaseId 和 folderId", async () => {
      (http.put as any).mockResolvedValue(undefined);
      await updateFolder(42, 10, { name: "改名" });
      expect(http.put).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/folders/10",
        { name: "改名" }
      );
    });

    it("deleteFolder 应 DELETE 并携带 knowledgeBaseId 和 folderId", async () => {
      (http.delete as any).mockResolvedValue(undefined);
      await deleteFolder(42, 10);
      expect(http.delete).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/folders/10"
      );
    });
  });

  describe("文档", () => {
    it("listDocuments 应 GET 并仅携带 current/size（后端不支持更多参数）", async () => {
      (http.get as any).mockResolvedValue({ records: [], total: 0 });
      await listDocuments(42, { current: 1, size: 10 });
      expect(http.get).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/documents",
        { params: { current: 1, size: 10 } }
      );
    });

    it("getDocument 应 GET /api/knowledge/documents/{documentId}", async () => {
      (http.get as any).mockResolvedValue({});
      await getDocument(100);
      expect(http.get).toHaveBeenCalledWith(
        "/api/knowledge/documents/100"
      );
    });

    it("deleteDocument 应 DELETE /api/knowledge/documents/{documentId}", async () => {
      (http.delete as any).mockResolvedValue(undefined);
      await deleteDocument(100);
      expect(http.delete).toHaveBeenCalledWith(
        "/api/knowledge/documents/100"
      );
    });

    it("文档预览、编辑和重新入库均使用 Gateway 的真实文档端点", async () => {
      await getDocumentContent(100);
      await updateDocument(100, { title: "新标题", folderId: 0, visibility: 3, status: 2 });
      await reingestDocument(100);
      expect(http.get).toHaveBeenCalledWith("/api/knowledge/documents/100/content", expect.objectContaining({ responseType: "blob" }));
      expect(http.put).toHaveBeenCalledWith("/api/knowledge/documents/100", { title: "新标题", folderId: 0, visibility: 3, status: 2 });
      expect(http.post).toHaveBeenCalledWith("/api/knowledge/documents/100/reingest");
    });
  });

  describe("成员", () => {
    it("listMembers 应 GET /api/knowledge/bases/{kbId}/members", async () => {
      (http.get as any).mockResolvedValue([]);
      await listMembers(42);
      expect(http.get).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/members"
      );
    });

    it("addMember 应 POST 并携带 userId 和 role", async () => {
      (http.post as any).mockResolvedValue(undefined);
      await addMember(42, { userId: 5, role: 3 });
      expect(http.post).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/members",
        { userId: 5, role: 3 }
      );
    });

    it("updateMemberRole 应 PUT 并携带 userId 在路径中", async () => {
      (http.put as any).mockResolvedValue(undefined);
      await updateMemberRole(42, 5, { role: 2 });
      expect(http.put).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/members/5",
        { role: 2 }
      );
    });

    it("removeMember 应 DELETE 并携带 userId 在路径中", async () => {
      (http.delete as any).mockResolvedValue(undefined);
      await removeMember(42, 5);
      expect(http.delete).toHaveBeenCalledWith(
        "/api/knowledge/bases/42/members/5"
      );
    });
  });
});
