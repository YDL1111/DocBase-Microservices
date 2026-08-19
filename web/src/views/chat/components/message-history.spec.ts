import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import MessageHistory from "./message-history.vue";

const stubs = { ElEmpty: { props: ["description"], template: "<div><slot />{{ description }}</div>" }, ElSkeleton: { template: "<div />" }, ElTag: { template: "<span><slot /></span>" }, ElIcon: { template: "<i><slot /></i>" }, ElButton: { props: ["icon", "loading", "disabled", "size", "type", "ariaLabel"], template: "<button><slot /></button>" } };
const message = (role: number, status: number, sourcesJson?: string) => ({ id: role * 10 + status, sessionId: 1, userId: 1, role, status, content: `message-${role}`, sourcesJson, createdAt: "2026-01-01" });

describe("chat message history", () => {
  it("renders numeric role and all message statuses as safe text", () => {
    const wrapper = mount(MessageHistory, { props: { selectedSessionId: 1, loading: false, streaming: false, syncing: false, cancelling: false, draining: false, canAcceptInput: true, attempt: null, messages: [message(1, 1), message(2, 2), message(3, 3), message(2, 4)] }, global: { stubs } });
    expect(wrapper.text()).toContain("用户");
    expect(wrapper.text()).toContain("助手");
    expect(wrapper.text()).toContain("系统");
    expect(wrapper.text()).toContain("生成中");
    expect(wrapper.text()).toContain("已完成");
    expect(wrapper.text()).toContain("失败");
    expect(wrapper.text()).toContain("已取消");
  });

  it("renders valid sources and safely ignores invalid sourcesJson without HTML injection", () => {
    const wrapper = mount(MessageHistory, { props: { selectedSessionId: 1, loading: false, streaming: false, syncing: false, cancelling: false, draining: false, canAcceptInput: true, attempt: null, messages: [message(2, 2, '[{"document_id":1,"file_name":"safe.pdf","page":3}]'), message(2, 2, "<img src=x onerror=alert(1)>")] }, global: { stubs } });
    expect(wrapper.text()).toContain("safe.pdf · 第 3 页");
    expect(wrapper.html()).not.toContain("<img src=x");
  });

  it("renders nullable source fields safely without object-storage details", () => {
    const wrapper = mount(MessageHistory, { props: { selectedSessionId: 1, loading: false, streaming: false, syncing: false, cancelling: false, draining: false, canAcceptInput: true, attempt: null, messages: [message(2, 2, '[{"document_id":1,"file_name":null,"page":null,"objectKey":"private"}]')] }, global: { stubs } });
    expect(wrapper.text()).toContain("文档");
    expect(wrapper.text()).not.toContain("private");
  });
});
