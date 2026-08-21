import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElMessageBox } from "element-plus";

const { listDocumentsMock, deleteDocumentMock, updateDocumentMock, reingestDocumentMock, getDocumentContentMock } = vi.hoisted(() => ({ listDocumentsMock: vi.fn(), deleteDocumentMock: vi.fn(), updateDocumentMock: vi.fn(), reingestDocumentMock: vi.fn(), getDocumentContentMock: vi.fn() }));
vi.mock("@/api/knowledge", () => ({
  listDocuments: (...args: unknown[]) => listDocumentsMock(...args),
  deleteDocument: (...args: unknown[]) => deleteDocumentMock(...args),
  updateDocument: (...args: unknown[]) => updateDocumentMock(...args),
  reingestDocument: (...args: unknown[]) => reingestDocumentMock(...args),
  getDocumentContent: (...args: unknown[]) => getDocumentContentMock(...args)
}));
vi.mock("@/utils/message", () => ({ message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() } }));

import DetailDocuments from "./detail-documents.vue";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";

function page(id: number, ingestStatus = 3) {
  return { records: [{ id, knowledgeBaseId: id, folderId: 0, title: `doc-${id}`, originalFilename: `doc-${id}.pdf`, objectKey: "server-only", contentType: "application/pdf", fileSize: 1, checksum: "x", ingestStatus, version: 1, status: 2, visibility: 1, createdBy: 1, updatedBy: 1, createdAt: "", updatedAt: "" }], total: 1, current: 1, size: 10, pages: 1 };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  return { promise: new Promise<T>(r => { resolve = r; }), resolve };
}
async function flush() { await flushPromises(); await nextTick(); }

describe("knowledge document list refresh isolation", () => {
  beforeEach(() => {
    listDocumentsMock.mockReset();
    deleteDocumentMock.mockReset();
    updateDocumentMock.mockReset();
    reingestDocumentMock.mockReset();
    getDocumentContentMock.mockReset();
    vi.useFakeTimers();
  });
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  function mountList(baseId: number) {
    return mount(DetailDocuments, {
      props: { knowledgeBaseId: baseId },
      global: {
        directives: { auth: { mounted() {} }, loading: { mounted() {}, updated() {} } },
        stubs: {
          DocumentUploadDialog: true,
          ElTable: { template: "<div />" },
          ElTableColumn: { template: "<div />" },
          ElPagination: { template: "<div />" },
          ElButton: { template: "<button><slot /></button>" },
          ElTag: { template: "<span><slot /></span>" }
        }
      }
    });
  }

  it("does not let a late base A response overwrite base B", async () => {
    const first = deferred<ReturnType<typeof page>>();
    listDocumentsMock.mockReturnValueOnce(first.promise).mockResolvedValueOnce(page(2));
    const store = useKnowledgeStoreHook();
    store.reset();
    store.beginBaseContext(1);
    const wrapper = mountList(1);

    store.beginBaseContext(2);
    await wrapper.setProps({ knowledgeBaseId: 2 });
    first.resolve(page(1));
    await flush();
    await flush();

    expect(listDocumentsMock).toHaveBeenNthCalledWith(1, 1, { current: 1, size: 10 });
    expect(listDocumentsMock).toHaveBeenNthCalledWith(2, 2, { current: 1, size: 10 });
    expect(store.documentList.map(document => document.id)).toEqual([2]);
    wrapper.unmount();
  });

  it("starts serial polling only for active ingest statuses and stops at terminal status", async () => {
    listDocumentsMock.mockResolvedValueOnce(page(1, 1)).mockResolvedValueOnce(page(1, 3));
    const store = useKnowledgeStoreHook();
    store.reset();
    store.beginBaseContext(1);
    const wrapper = mountList(1);
    await flush();
    expect(vi.getTimerCount()).toBe(1);

    await vi.runOnlyPendingTimersAsync();
    await flush();
    expect(listDocumentsMock).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
    wrapper.unmount();
  });

  it("edits document metadata and creates a reingest task from the document row", async () => {
    listDocumentsMock.mockResolvedValue(page(1, 3));
    updateDocumentMock.mockResolvedValue(undefined);
    reingestDocumentMock.mockResolvedValue(undefined);
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const store = useKnowledgeStoreHook();
    store.reset();
    store.beginBaseContext(1);
    const wrapper = mountList(1);
    await flush();
    const row = store.documentList[0];

    (wrapper.vm as any).openEdit(row);
    (wrapper.vm as any).editForm.title = "更新后的标题";
    await (wrapper.vm as any).submitEdit();
    expect(updateDocumentMock).toHaveBeenCalledWith(1, expect.objectContaining({ title: "更新后的标题" }));

    await (wrapper.vm as any).handleReingest(row);
    expect(reingestDocumentMock).toHaveBeenCalledWith(1);
    wrapper.unmount();
  });

  it("locks reingest before confirmation so a double click cannot enqueue twice", async () => {
    listDocumentsMock.mockResolvedValue(page(1, 3));
    let approve!: () => void;
    vi.spyOn(ElMessageBox, "confirm").mockImplementationOnce(() => new Promise(resolve => { approve = () => resolve("confirm" as never); }));
    reingestDocumentMock.mockResolvedValue(undefined);
    const store = useKnowledgeStoreHook();
    store.reset();
    store.beginBaseContext(1);
    const wrapper = mountList(1);
    await flush();
    const row = store.documentList[0];

    const first = (wrapper.vm as any).handleReingest(row);
    const second = (wrapper.vm as any).handleReingest(row);
    expect(ElMessageBox.confirm).toHaveBeenCalledOnce();
    approve();
    await first;
    await second;
    expect(reingestDocumentMock).toHaveBeenCalledOnce();
    wrapper.unmount();
  });

  it("publishes a draft document explicitly so it becomes eligible for AI chat", async () => {
    const draftPage = page(1, 3);
    draftPage.records[0].status = 1;
    listDocumentsMock.mockResolvedValue(draftPage);
    updateDocumentMock.mockResolvedValue(undefined);
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const store = useKnowledgeStoreHook();
    store.reset();
    store.beginBaseContext(1);
    const wrapper = mountList(1);
    await flush();

    await (wrapper.vm as any).handlePublishToggle(store.documentList[0]);

    expect(updateDocumentMock).toHaveBeenCalledWith(1, {
      title: "doc-1",
      folderId: 0,
      visibility: 1,
      status: 2
    });
    wrapper.unmount();
  });
});
