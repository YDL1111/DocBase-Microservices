import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { AxiosError, type AxiosResponse } from "axios";

const { uploadDocumentMock, messages } = vi.hoisted(() => ({
  uploadDocumentMock: vi.fn(),
  messages: { success: vi.fn(), warning: vi.fn(), info: vi.fn(), error: vi.fn() }
}));
vi.mock("@/api/knowledge", () => ({ uploadDocument: (...args: unknown[]) => uploadDocumentMock(...args) }));
vi.mock("@/utils/message", () => ({ message: messages }));

import DocumentUploadDialog from "./document-upload-dialog.vue";

const stubs = {
  ElDialog: { template: "<section v-if='modelValue'><slot /><slot name='footer' /></section>", props: ["modelValue"] },
  ElForm: { template: "<form><slot /></form>", setup(_props: unknown, { expose }: { expose: (value: unknown) => void }) { expose({ validate: () => Promise.resolve(true), clearValidate: vi.fn() }); } },
  ElFormItem: { template: "<div><slot /></div>" },
  ElSwitch: { template: "<button type='button'><slot /></button>", props: ["modelValue"] },
  ElUpload: { name: "ElUpload", props: ["onChange"], template: "<div><slot /><slot name='tip' /></div>" },
  ElTreeSelect: { template: "<select />" },
  ElRadioGroup: { template: "<div><slot /></div>" },
  ElRadio: { template: "<label><slot /></label>" },
  ElProgress: { template: "<div />" },
  ElIcon: { template: "<i><slot /></i>" },
  ElInput: { name: "ElInput", emits: ["update:modelValue"], template: "<input />" },
  ElButton: { emits: ["click"], template: "<button @click='$emit(\"click\")'><slot /></button>" }
};

function mountDialog() {
  return mount(DocumentUploadDialog, {
    props: { modelValue: true, knowledgeBaseId: 8, folderTree: [{ id: 12, parentId: 0, name: "Nested", sortNum: 0 }], defaultFolderId: 12 },
    global: { stubs: stubs as any }
  });
}
function uploadButton(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll("button").find(button => button.text().includes("开始上传"))!;
}
async function flush(): Promise<void> {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

function backendError(code: string, status = 400): AxiosError {
  const config = { headers: {} } as any;
  const response = {
    data: { success: false, code, message: code },
    status,
    statusText: "Request failed",
    headers: {},
    config,
    request: {}
  } as AxiosResponse;
  return new AxiosError(`Request failed with status code ${status}`, "ERR_BAD_REQUEST", config, {}, response);
}

function selectFile(wrapper: ReturnType<typeof mount>, name = "guide.pdf"): void {
  const upload = wrapper.findComponent({ name: "ElUpload" });
  (upload.props().onChange as Function)({ name, raw: new File(["x"], name, { lastModified: 1 }) });
}

describe("document upload dialog", () => {
  beforeEach(() => {
    uploadDocumentMock.mockReset();
    Object.values(messages).forEach(fn => fn.mockReset());
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValue("attempt-one") });
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("blocks unsupported files before creating a request", async () => {
    const wrapper = mountDialog();
    const upload = wrapper.findComponent({ name: "ElUpload" });
    (upload.props().onChange as Function)({ name: "unsafe.exe", raw: new File(["x"], "unsafe.exe") });
    await nextTick();
    expect(uploadDocumentMock).not.toHaveBeenCalled();
    expect(messages.error).toHaveBeenCalled();
  });

  it("uploads one file with a generated id and emits the source knowledge base", async () => {
    uploadDocumentMock.mockResolvedValue(99);
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(uploadDocumentMock).toHaveBeenCalledWith(8, expect.objectContaining({
      clientRequestId: "attempt-one", title: "guide", folderId: 12, visibility: 1, publishForChat: true
    }), expect.any(Object));
    expect(wrapper.emitted("uploaded")).toEqual([[99, 8]]);
    expect(messages.success).toHaveBeenCalled();
  });

  it("reuses the same id when a real 5xx Axios failure is retried", async () => {
    uploadDocumentMock.mockRejectedValueOnce(backendError("INTERNAL_ERROR", 500)).mockResolvedValueOnce(100);
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(uploadDocumentMock.mock.calls.map(call => call[1].clientRequestId)).toEqual(["attempt-one", "attempt-one"]);
    expect(messages.warning).toHaveBeenCalledTimes(1);
  });

  it("treats an Axios timeout as uncertain and reuses the attempt id", async () => {
    uploadDocumentMock.mockRejectedValueOnce(new AxiosError("timeout of 120000ms exceeded", "ECONNABORTED"))
      .mockResolvedValueOnce(105);
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(uploadDocumentMock.mock.calls.map(call => call[1].clientRequestId)).toEqual(["attempt-one", "attempt-one"]);
    expect(messages.warning).toHaveBeenCalledTimes(1);
  });

  it("reads UPLOAD_IN_PROGRESS from an Axios HTTP 400 response and refreshes the source list", async () => {
    uploadDocumentMock.mockRejectedValueOnce(backendError("UPLOAD_IN_PROGRESS"));
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(wrapper.emitted("refresh")).toEqual([[8]]);
    expect(messages.info).toHaveBeenCalledTimes(1);
  });

  it("creates a fresh id after IDEMPOTENCY_CONFLICT from an Axios HTTP 400 response", async () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValueOnce("attempt-one").mockReturnValueOnce("attempt-two") });
    uploadDocumentMock.mockRejectedValueOnce(backendError("IDEMPOTENCY_CONFLICT")).mockResolvedValueOnce(101);
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(uploadDocumentMock.mock.calls.map(call => call[1].clientRequestId)).toEqual(["attempt-one", "attempt-two"]);
    expect(messages.warning).toHaveBeenCalledTimes(1);
  });

  it("shows exactly one upload-specific message for a forbidden Axios response", async () => {
    uploadDocumentMock.mockRejectedValueOnce(backendError("FORBIDDEN", 403));
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(messages.error).toHaveBeenCalledTimes(1);
  });

  it("creates a fresh id when metadata changes after an uncertain result", async () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValueOnce("attempt-one").mockReturnValueOnce("attempt-two") });
    uploadDocumentMock.mockRejectedValueOnce(backendError("INTERNAL_ERROR", 500)).mockResolvedValueOnce(102);
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();
    wrapper.findComponent({ name: "ElInput" }).vm.$emit("update:modelValue", "renamed");
    await flush();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(uploadDocumentMock.mock.calls.map(call => call[1].clientRequestId)).toEqual(["attempt-one", "attempt-two"]);
    expect(uploadDocumentMock.mock.calls[1][1].title).toBe("renamed");
  });

  it("clears a failed attempt when the knowledge base changes", async () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValueOnce("attempt-one").mockReturnValueOnce("attempt-two") });
    uploadDocumentMock.mockRejectedValueOnce(backendError("INTERNAL_ERROR", 500)).mockResolvedValueOnce(103);
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();
    await wrapper.setProps({ knowledgeBaseId: 9 });
    await flush();
    selectFile(wrapper, "other.pdf");
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();

    expect(uploadDocumentMock.mock.calls.map(call => [call[0], call[1].clientRequestId])).toEqual([[8, "attempt-one"], [9, "attempt-two"]]);
    expect(wrapper.emitted("update:modelValue")).toContainEqual([false]);
  });

  it("keeps an in-flight request bound to its original knowledge base, then clears it after a switch", async () => {
    let complete!: (documentId: number) => void;
    uploadDocumentMock.mockImplementationOnce(() => new Promise<number>(resolve => { complete = resolve; }));
    const wrapper = mountDialog();
    selectFile(wrapper);
    await nextTick();
    await uploadButton(wrapper).trigger("click");
    await flush();
    await wrapper.setProps({ knowledgeBaseId: 9 });
    complete(104);
    await flush();

    expect(uploadDocumentMock).toHaveBeenCalledWith(8, expect.any(Object), expect.any(Object));
    expect(wrapper.emitted("uploaded")).toEqual([[104, 8]]);
    expect(wrapper.emitted("update:modelValue")).toContainEqual([false]);
  });
});
