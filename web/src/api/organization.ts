import { http } from "@/utils/request";
import type { OrganizationRequest, SysOrganization } from "./types";

function positiveSafeInteger(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new RangeError(`${field} must be a positive safe integer`);
  }
}

export function listOrganizations(): Promise<SysOrganization[]> {
  return http.get<SysOrganization[]>("/api/system/organizations");
}

export function createOrganization(data: OrganizationRequest): Promise<number> {
  return http.post<number>("/api/system/organizations", data);
}

export function updateOrganization(id: number, data: OrganizationRequest): Promise<void> {
  positiveSafeInteger(id, "organizationId");
  return http.put<void>(`/api/system/organizations/${id}`, data);
}

export function deleteOrganization(id: number): Promise<void> {
  positiveSafeInteger(id, "organizationId");
  return http.delete<void>(`/api/system/organizations/${id}`);
}
