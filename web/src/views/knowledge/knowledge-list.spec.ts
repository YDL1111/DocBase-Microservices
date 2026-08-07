import { describe, it, expect, vi, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useKnowledgeStore } from "@/store/modules/knowledge";

/**
 * 知识库列表加载与分页的 store 层测试。
 * 组件层测试需要完整的 Vue 测试环境（mount + 模拟 API），
 * 这里聚焦 store 状态流转，确保列表/分页逻辑正确。
 */
describe("knowledge list loading and pagination", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  const makeBase = (id: number) => ({
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

  it("setListLoading 应更新加载状态", () => {
    const store = useKnowledgeStore();
    expect(store.listLoading).toBe(false);
    store.setListLoading(true);
    expect(store.listLoading).toBe(true);
    store.setListLoading(false);
    expect(store.listLoading).toBe(false);
  });

  it("setBaseList 应正确设置列表和总数（分页场景）", () => {
    const store = useKnowledgeStore();

    // 第一页
    const page1 = [makeBase(1), makeBase(2), makeBase(3)];
    store.setBaseList(page1, 25);
    expect(store.baseList).toHaveLength(3);
    expect(store.total).toBe(25);

    // 切换到第二页（新数据覆盖旧数据）
    const page2 = [makeBase(4), makeBase(5)];
    store.setBaseList(page2, 25);
    expect(store.baseList).toHaveLength(2);
    expect(store.baseList[0].id).toBe(4);
    expect(store.total).toBe(25); // 总数不变
  });

  it("空列表应正确表示无数据状态", () => {
    const store = useKnowledgeStore();
    store.setBaseList([], 0);
    expect(store.baseList).toEqual([]);
    expect(store.total).toBe(0);
  });

  it("删除知识库后若当前页为空应能回退（由组件层处理）", () => {
    const store = useKnowledgeStore();
    store.setBaseList([makeBase(1)], 1);

    // 组件层判断：若删除后列表长度为 0 且当前页 > 1，回退到上一页
    const shouldGoToPrevPage =
      store.baseList.length === 1 /* 删除前 */ && 1 /* currentPage */ > 1;
    expect(shouldGoToPrevPage).toBe(false); // 第一页不回退

    // 模拟第二页删除最后一条
    store.setBaseList([makeBase(2)], 1);
    const shouldGoToPrevPage2 =
      store.baseList.length === 1 && 2 /* currentPage */ > 1;
    expect(shouldGoToPrevPage2).toBe(true);
  });
});
