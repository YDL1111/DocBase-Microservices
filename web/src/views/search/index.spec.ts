import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import SearchPage from "./index.vue";
const api = vi.hoisted(() => ({ listKnowledgeBases: vi.fn(), searchKnowledge: vi.fn(), getDocumentContent: vi.fn() }));
vi.mock("@/api/knowledge", () => api);
vi.mock("vue-router", () => ({ useRouter: () => ({ push: vi.fn() }) }));
beforeEach(() => {
  vi.resetAllMocks();
  api.listKnowledgeBases.mockResolvedValue({ records: [{ id: 2, name: "项目文档", status: 1 }], total: 1 });
});
async function setup() {
  const wrapper = mount(SearchPage, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  await wrapper.get('input[aria-label="检索内容"]').setValue('部署');
  return wrapper;
}
describe("知识检索", () => {
  it("summarizes documents and expands long passages", async () => {
    api.searchKnowledge.mockResolvedValue([{documentId:9,title:'指南',content:'有效内容'.repeat(100)}]);
    const wrapper = await setup(); await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(wrapper.text()).toContain('来自 1 份文档');
    expect(wrapper.find('.passage').classes()).toContain('collapsed');
    const expand = wrapper.findAll('button').find(button => button.text() === '展开完整片段');
    await expand!.trigger('click');
    expect(wrapper.find('.passage').classes()).not.toContain('collapsed');
    expect(wrapper.text()).toContain('收起片段'); wrapper.unmount();
  });

  it("calls the scoped API and renders original text without executing HTML", async () => {
    api.searchKnowledge.mockResolvedValue([{ documentId: 9, title: '部署指南', filename: 'guide.pdf', content: '<script>alert(1)</script>正文', page: 2 }]);
    const wrapper = await setup(); await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(api.searchKnowledge).toHaveBeenCalledWith(2, '部署', expect.any(AbortSignal));
    expect(wrapper.findAll('.search-result')).toHaveLength(1);
    expect(wrapper.text()).toContain('页码 2'); expect(wrapper.text()).toContain('<script>');
    expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.text()).toContain('下载原文');
    wrapper.unmount();
  });
  it("shows empty and error states separately", async () => {
    api.searchKnowledge.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('unavailable'));
    const wrapper = await setup(); await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(wrapper.text()).toContain('没有找到相关片段');
    await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(wrapper.text()).toContain('暂时无法检索'); wrapper.unmount();
  });
  it("explains scope limits instead of pretending there are no matches", async () => {
    api.searchKnowledge.mockRejectedValue(new Error('SEARCH_SCOPE_TOO_LARGE'));
    const wrapper = await setup(); await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(wrapper.text()).toContain('超过 1000 份');
    expect(wrapper.text()).not.toContain('没有找到相关片段'); wrapper.unmount();
  });
  it("provides a retry when knowledge bases cannot load", async () => {
    api.listKnowledgeBases.mockRejectedValueOnce(new Error('offline'));
    const wrapper = mount(SearchPage, { global: { plugins: [ElementPlus] } });
    await flushPromises(); expect(wrapper.text()).toContain('知识库加载失败');
    expect(wrapper.text()).toContain('重新加载知识库'); wrapper.unmount();
  });
  it("ignores an old result when the query changes", async () => {
    let resolve!: (value: unknown[]) => void;
    api.searchKnowledge.mockImplementation(() => new Promise(r => { resolve = r; }));
    const wrapper = await setup(); await wrapper.get('form').trigger('submit');
    await wrapper.get('input[aria-label="检索内容"]').setValue('其他问题');
    resolve([{ documentId: 9, content: 'stale', title: 'old' }]); await flushPromises();
    expect(wrapper.findAll('.search-result')).toHaveLength(0); wrapper.unmount();
  });
});
