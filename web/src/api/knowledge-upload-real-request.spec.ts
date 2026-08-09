import { afterEach, describe, expect, it, vi } from "vitest";
import { AxiosError, type AxiosResponse } from "axios";

const { globalMessages } = vi.hoisted(() => ({ globalMessages: { error: vi.fn() } }));
vi.mock("@/utils/message", () => ({ message: globalMessages }));

import { service } from "@/utils/request";
import { uploadDocument } from "./knowledge";

const originalAdapter = service.defaults.adapter;

afterEach(() => {
  service.defaults.adapter = originalAdapter;
  globalMessages.error.mockReset();
});

describe("knowledge upload through the real Axios instance", () => {
  it("keeps FormData through Axios request transforms instead of JSON serializing it", async () => {
    let sentData: unknown;
    let sentContentType: unknown;
    service.defaults.adapter = async config => {
      sentData = config.data;
      sentContentType = config.headers.getContentType();
      return {
        data: { success: true, code: "OK", message: "", data: 42 },
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {}
      } as AxiosResponse;
    };

    await expect(uploadDocument(7, {
      file: new File(["hello"], "report.pdf", { type: "application/pdf" }),
      clientRequestId: "attempt-1"
    })).resolves.toBe(42);

    expect(sentData).toBeInstanceOf(FormData);
    expect(typeof sentData).not.toBe("string");
    expect(sentContentType).not.toBe("application/json");
  });

  it("preserves the backend business code and HTTP status after the response interceptor", async () => {
    service.defaults.adapter = async config => {
      const response = {
        data: { success: false, code: "UPLOAD_IN_PROGRESS", message: "still uploading" },
        status: 400,
        statusText: "Bad Request",
        headers: {},
        config,
        request: {}
      } as AxiosResponse;
      throw new AxiosError("Request failed with status code 400", "ERR_BAD_REQUEST", config, {}, response);
    };

    await expect(uploadDocument(7, {
      file: new File(["hello"], "report.pdf", { type: "application/pdf" }),
      clientRequestId: "attempt-1"
    })).rejects.toMatchObject({
      response: { status: 400, data: { code: "UPLOAD_IN_PROGRESS" } }
    });
    expect(globalMessages.error).not.toHaveBeenCalled();
  });

  it.each([400, 403, 500])("suppresses the global message for upload HTTP %i failures", async status => {
    service.defaults.adapter = async config => {
      const response = {
        data: { success: false, code: "UPLOAD_FAILED", message: "upload failed" },
        status,
        statusText: "Request failed",
        headers: {},
        config,
        request: {}
      } as AxiosResponse;
      throw new AxiosError(`Request failed with status code ${status}`, "ERR_BAD_RESPONSE", config, {}, response);
    };

    await expect(uploadDocument(7, {
      file: new File(["hello"], "report.pdf", { type: "application/pdf" }),
      clientRequestId: "attempt-1"
    })).rejects.toBeInstanceOf(AxiosError);

    expect(globalMessages.error).not.toHaveBeenCalled();
  });
});
