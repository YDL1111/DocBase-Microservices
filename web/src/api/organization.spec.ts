import { beforeEach, describe, expect, it, vi } from "vitest";
import { createOrganization, deleteOrganization, listOrganizations, updateOrganization } from "./organization";

vi.mock("@/utils/request", () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}));
import { http } from "@/utils/request";

describe("organization api", () => {
  beforeEach(() => { vi.clearAllMocks(); });

  const payload = { parentId: 0, organizationName: "研发中心", organizationCode: "docbase_rd", sortNum: 10, status: 1, remark: "" };

  it("lists organizations", async () => {
    await listOrganizations();
    expect(http.get).toHaveBeenCalledWith("/api/system/organizations");
  });

  it("creates organization with explicit fields", async () => {
    await createOrganization(payload);
    expect(http.post).toHaveBeenCalledWith("/api/system/organizations", payload);
  });

  it("updates and deletes by safe id", async () => {
    await updateOrganization(3, payload);
    await deleteOrganization(3);
    expect(http.put).toHaveBeenCalledWith("/api/system/organizations/3", payload);
    expect(http.delete).toHaveBeenCalledWith("/api/system/organizations/3");
  });

  it("rejects unsafe ids before requesting", () => {
    expect(() => updateOrganization(0, payload)).toThrow(RangeError);
    expect(() => deleteOrganization(Number.MAX_SAFE_INTEGER + 1)).toThrow(RangeError);
  });
});
