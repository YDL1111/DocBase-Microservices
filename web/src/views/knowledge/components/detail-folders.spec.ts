import { defineComponent } from "vue";
import { mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import DetailFolders from "./detail-folders.vue";

const { knowledgeState } = vi.hoisted(() => ({
  knowledgeState: {
    currentBase: null,
    folderTree: [
      {
        id: 12,
        parentId: 0,
        name: "产品资料",
        sortNum: 1,
        children: []
      }
    ],
    getRequestSeq: vi.fn(() => 1),
    setFolderTree: vi.fn()
  }
}));

vi.mock("@/store/modules/knowledge", () => ({
  useKnowledgeStoreHook: () => knowledgeState
}));

vi.mock("@/api/knowledge", () => ({
  getFolderTree: vi.fn(),
  createFolder: vi.fn(),
  updateFolder: vi.fn(),
  deleteFolder: vi.fn()
}));

const ElButton = defineComponent({
  name: "ElButton",
  emits: ["click"],
  template: '<button @click="$emit(\'click\')"><slot /></button>'
});

const ElDialog = defineComponent({
  name: "ElDialog",
  props: { modelValue: Boolean, title: String },
  template:
    '<section v-if="modelValue" class="dialog-stub"><h2>{{ title }}</h2><slot /><slot name="footer" /></section>'
});

const ElTreeSelect = defineComponent({
  name: "ElTreeSelect",
  props: ["modelValue", "data", "props"],
  template: '<div class="tree-select-stub" />'
});

function mountFolders() {
  return mount(DetailFolders, {
    props: { knowledgeBaseId: 7 },
    global: {
      directives: {
        auth: { mounted() {} },
        loading: { mounted() {} }
      },
      stubs: {
        ElButton,
        ElDialog,
        ElTreeSelect,
        ElTree: { template: '<div class="tree-stub" />' },
        ElForm: { template: "<form><slot /></form>" },
        ElFormItem: { template: "<div><slot /></div>" },
        ElInput: { template: "<input />" },
        ElInputNumber: { template: "<input type=number />" },
        ElIcon: { template: "<i><slot /></i>" }
      }
    }
  });
}

describe("知识库文档分类", () => {
  beforeEach(() => {
    knowledgeState.currentBase = null;
  });

  it("提供明确入口，并使用分类树选择上级分类", async () => {
    const wrapper = mountFolders();

    expect(wrapper.text()).toContain("文档分类");
    expect(wrapper.text()).toContain("新建分类");
    await wrapper.find("button").trigger("click");

    const selector = wrapper.findComponent(ElTreeSelect);
    expect(selector.exists()).toBe(true);
    expect(selector.props("data")).toEqual([
      expect.objectContaining({
        id: 0,
        name: "顶级分类",
        children: [expect.objectContaining({ id: 12, name: "产品资料" })]
      })
    ]);
  });
});
