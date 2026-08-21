import { beforeEach, describe, expect, it, vi } from "vitest";

const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("@/utils/request", () => ({ http: { post } }));

import { DOCUMENT_UPLOAD_TIMEOUT, uploadDocument } from "./knowledge";

describe("knowledge multipart upload API", () => {
  beforeEach(() => post.mockReset().mockResolvedValue(42));

  it("sends the real multipart contract without object or internal headers", async () => {
    const file = new File(["hello"], "report.pdf", { type: "application/pdf" });
    const onUploadProgress = vi.fn();

    await expect(uploadDocument(7, {
      file, clientRequestId: "attempt-1", title: "Report", folderId: 0, visibility: 3, publishForChat: true
    }, { onUploadProgress })).resolves.toBe(42);

    expect(post).toHaveBeenCalledOnce();
    const [url, formData, config] = post.mock.calls[0];
    expect(url).toBe("/api/knowledge/bases/7/documents/upload");
    expect(formData).toBeInstanceOf(FormData);
    expect(formData.get("file")).toBe(file);
    expect(formData.get("publishForChat")).toBe("true");
    expect(formData.get("clientRequestId")).toBe("attempt-1");
    expect(formData.get("title")).toBe("Report");
    expect(formData.get("folderId")).toBe("0");
    expect(formData.get("visibility")).toBe("3");
    expect(Array.from(formData.keys())).not.toEqual(expect.arrayContaining(["objectKey", "X-User-Id", "X-Knowledge-Internal-Key"]));
    expect(config.timeout).toBe(DOCUMENT_UPLOAD_TIMEOUT);
    expect(config.headers).toBeUndefined();
    expect(config.skipGlobalErrorMessage).toBe(true);

    config.onUploadProgress({ loaded: 50, total: 40 });
    config.onUploadProgress({ loaded: 10, total: undefined });
    expect(onUploadProgress).toHaveBeenNthCalledWith(1, 100);
    expect(onUploadProgress).toHaveBeenNthCalledWith(2, 0);
  });
});
