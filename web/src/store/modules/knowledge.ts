/**
 * Knowledge 知识库状态管理。
 *
 * 设计要点：
 *  - 当前选中的知识库（currentBase）及其目录树、文档列表、成员列表集中管理；
 *  - 切换知识库时自动重置目录/文档/成员状态，防止状态串到其他知识库；
 *  - knowledgeBaseId 只来自路由参数或当前上下文，不信任外部传入。
 */
import { defineStore } from "pinia";
import { store } from "@/store";
import type {
  KnowledgeBase,
  FolderNode,
  KnowledgeDocument,
  KnowledgeMember
} from "@/api/types";

export interface KnowledgeState {
  /** 知识库列表 */
  baseList: KnowledgeBase[];
  /** 列表加载状态 */
  listLoading: boolean;
  /** 列表总数量 */
  total: number;
  /** 当前选中的知识库 */
  currentBase: KnowledgeBase | null;
  /** 当前知识库的目录树 */
  folderTree: FolderNode[];
  /** 当前知识库的文档列表 */
  documentList: KnowledgeDocument[];
  /** 文档总数 */
  documentTotal: number;
  /** 当前选中的目录 ID（0 表示根） */
  currentFolderId: number;
  /** 当前知识库的成员列表 */
  members: KnowledgeMember[];
  /**
   * 请求序号。每次切换知识库时递增。
   * 组件在发起异步请求前捕获此值，写入 Store 时校验。
   * 若序号已变化（说明已切换到其他知识库），则丢弃响应，防止串库。
   */
  requestSeq: number;
  /**
   * 当前上下文的知识库 ID。
   * 用于判断 beginBaseContext 是否真的切换了知识库。
   * 与 currentBase 不同：即使详情数据未加载，上下文也已建立。
   */
  currentContextId: number | null;
}

export const useKnowledgeStore = defineStore({
  id: "docbase-knowledge",
  state: (): KnowledgeState => ({
    baseList: [],
    listLoading: false,
    total: 0,
    currentBase: null,
    folderTree: [],
    documentList: [],
    documentTotal: 0,
    currentFolderId: 0,
    members: [],
    requestSeq: 0,
    currentContextId: null
  }),
  getters: {
    /** 当前知识库 ID（便捷访问） */
    currentBaseId: (state): number | null => state.currentBase?.id ?? null
  },
  actions: {
    /** 设置知识库列表 */
    setBaseList(list: KnowledgeBase[], total: number) {
      this.baseList = list;
      this.total = total;
    },
    setListLoading(loading: boolean) {
      this.listLoading = loading;
    },
    /**
     * 开始新的知识库上下文。
     *
     * 仅在路由参数变化（切换到不同知识库）时调用。
     * 递增 requestSeq 使进行中的旧请求写入时被丢弃，并清空子状态。
     *
     * 注意：不要在详情响应返回时调用此方法！
     * 详情响应应使用 setCurrentBase(base, seq) 写入，避免递增序号。
     */
    beginBaseContext(baseId: number): void {
      // 只有真正切换知识库时才递增序号
      if (this.currentContextId === baseId) return;
      this.requestSeq += 1;
      this.currentContextId = baseId;
      this.currentBase = null;
      this.folderTree = [];
      this.documentList = [];
      this.documentTotal = 0;
      this.currentFolderId = 0;
      this.members = [];
    },
    /**
     * 设置当前知识库详情数据。
     *
     * @param base 知识库详情
     * @param seq 发起详情请求时捕获的序号（可选）。
     *           若提供且已变化，则丢弃（说明已切换到其他知识库）。
     *           不递增序号，避免影响并发的子请求。
     */
    setCurrentBase(base: KnowledgeBase | null, seq?: number): void {
      if (seq !== undefined && seq !== this.requestSeq) return;
      this.currentBase = base;
    },
    /** 获取当前请求序号（组件发起异步请求前捕获） */
    getRequestSeq(): number {
      return this.requestSeq;
    },
    /**
     * 设置目录树（带序号校验）。
     * @param seq 发起请求时捕获的序号，若已变化则丢弃
     */
    setFolderTree(tree: FolderNode[], seq?: number) {
      if (seq !== undefined && seq !== this.requestSeq) return;
      this.folderTree = tree;
    },
    /**
     * 设置文档列表（带序号校验）。
     */
    setDocumentList(list: KnowledgeDocument[], total: number, seq?: number) {
      if (seq !== undefined && seq !== this.requestSeq) return;
      this.documentList = list;
      this.documentTotal = total;
    },
    /** 设置当前目录 */
    setCurrentFolderId(folderId: number) {
      this.currentFolderId = folderId;
    },
    /**
     * 设置成员列表（带序号校验）。
     */
    setMembers(members: KnowledgeMember[], seq?: number) {
      if (seq !== undefined && seq !== this.requestSeq) return;
      this.members = members;
    },
    /** 从成员列表中移除指定用户 */
    removeMember(userId: number) {
      this.members = this.members.filter(m => m.userId !== userId);
    },
    /** 更新成员角色 */
    updateMemberRole(userId: number, role: number) {
      const member = this.members.find(m => m.userId === userId);
      if (member) {
        member.memberRole = role;
      }
    },
    /** 完全重置（登出时） */
    reset() {
      this.baseList = [];
      this.listLoading = false;
      this.total = 0;
      this.currentBase = null;
      this.folderTree = [];
      this.documentList = [];
      this.documentTotal = 0;
      this.currentFolderId = 0;
      this.members = [];
      this.requestSeq += 1;
      this.currentContextId = null;
    }
  }
});

export function useKnowledgeStoreHook() {
  return useKnowledgeStore(store);
}
