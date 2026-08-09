import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";

const { listDocumentsMock, deleteDocumentMock } = vi.hoisted(() => ({ listDocumentsMock: vi.fn(), deleteDocumentMock: vi.fn() }));
vi.mock("@/api/knowledge", () => ({
  listDocuments: (...args: unknown[]) => listDocumentsMock(...args),
  deleteDocument: (...args: unknown[]) => deleteDocumentMock(...args)
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
});
