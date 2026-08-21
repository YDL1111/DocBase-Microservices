// @vitest-environment jsdom

import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import MessageHistory from "./message-history.vue";
import { renderAnswerMarkdown } from "../chat-markdown";

const stubs = {
  ElSkeleton: { template: "<div />" },
  ElTag: { template: "<span><slot /></span>" },
  ElIcon: { template: "<i><slot /></i>" },
  ElButton: {
    props: ["icon", "loading", "disabled", "size", "type"],
    emits: ["click"],
    template: "<button :disabled='disabled' @click='$emit(\"click\")'><slot /></button>"
  }
};

const message = (role: number, status: number, sourcesJson?: string) => ({
  id: role * 10 + status,
  sessionId: 1,
  userId: 1,
  role,
  status,
  content: `message-${role}`,
  sourcesJson,
  createdAt: "2026-01-01T00:00:00.000Z"
});

function mountHistory(messages: ReturnType<typeof message>[]) {
  return mount(MessageHistory, {
    props: {
      selectedSessionId: 1,
      loading: false,
      streaming: false,
      syncing: false,
      cancelling: false,
      draining: false,
      canAcceptInput: true,
      deletingMessageId: null,
      attempt: null,
      messages
    },
    global: { stubs }
  });
}

describe("chat message history", () => {
  it("renders numeric role and all message statuses as safe text", () => {
    const wrapper = mountHistory([message(1, 1), message(2, 2), message(3, 3), message(2, 4)]);
    expect(wrapper.text()).toContain("用户");
    expect(wrapper.text()).toContain("助手");
    expect(wrapper.text()).toContain("系统");
    expect(wrapper.text()).toContain("生成中");
    expect(wrapper.text()).toContain("已完成");
    expect(wrapper.text()).toContain("失败");
    expect(wrapper.text()).toContain("已取消");
  });

  it("keeps citations outside the answer body and expands them on demand", async () => {
    const wrapper = mountHistory([
      message(2, 2, '[{"document_id":1,"file_name":"safe.pdf","page":3}]'),
      message(2, 2, "<img src=x onerror=alert(1)>")
    ]);
    expect(wrapper.find(".message__content").text()).not.toContain("safe.pdf");
    expect(wrapper.text()).toContain("引用 1 项材料");
    expect(wrapper.text()).not.toContain("safe.pdf · 第 3 页");
    await wrapper.find(".message__references-toggle").trigger("click");
    expect(wrapper.text()).toContain("safe.pdf · 第 3 页");
    expect(wrapper.html()).not.toContain("<img src=x");
  });

  it("renders assistant Markdown safely and removes inline source markers from the body", () => {
    const assistant = {
      ...message(2, 2, '[{"document_id":1,"file_name":"八大车间解说.docx","page":1}]'),
      content: "## 八大车间\n\n1. **算力车间**：动力中心。【来源：八大车间解说.docx]\n\n<script>alert(1)</script><img src=x onerror=alert(2)>"
    };
    const wrapper = mountHistory([assistant]);

    expect(renderAnswerMarkdown(assistant.content)).toContain("<h2>八大车间</h2>");
    expect(wrapper.find(".message__markdown h2").exists(), wrapper.html()).toBe(true);
    expect(wrapper.find(".message__markdown h2").text()).toBe("八大车间");
    expect(wrapper.find(".message__markdown strong").text()).toBe("算力车间");
    expect(wrapper.find(".message__content").text()).not.toContain("来源");
    expect(wrapper.find(".message__content").text()).not.toContain("八大车间解说.docx");
    expect(wrapper.find(".message__content script").exists()).toBe(false);
    expect(wrapper.find(".message__content img").exists()).toBe(false);
  });

  it("renders nullable source fields safely without object-storage details", async () => {
    const wrapper = mountHistory([message(2, 2, '[{"document_id":1,"file_name":null,"page":null,"objectKey":"private"}]')]);
    await wrapper.find(".message__references-toggle").trigger("click");
    expect(wrapper.text()).toContain("文档");
    expect(wrapper.text()).not.toContain("private");
  });

  it("emits only the requested user and assistant actions", async () => {
    const user = message(1, 2);
    const assistant = { ...message(2, 2), completedAt: "2026-01-01T00:00:08.450Z" };
    const wrapper = mountHistory([user, assistant]);
    const actions = wrapper.findAll(".message__actions");
    expect(actions[0].findAll("button")).toHaveLength(2);
    expect(actions[1].findAll("button")).toHaveLength(2);
    expect(actions[1].text()).toContain("8.45s");
    await actions[0].findAll("button")[0].trigger("click");
    await actions[0].findAll("button")[1].trigger("click");
    await actions[1].findAll("button")[0].trigger("click");
    await actions[1].findAll("button")[1].trigger("click");
    expect(wrapper.emitted("copy")).toEqual([[user], [assistant]]);
    expect(wrapper.emitted("resend")).toEqual([[user]]);
    expect(wrapper.emitted("delete")).toEqual([[assistant]]);
  });

  it("does not invent a duration when timestamps are missing or invalid", () => {
    const invalid = { ...message(2, 2), completedAt: "invalid" };
    const missing = message(2, 2);
    const wrapper = mountHistory([invalid, missing]);
    expect(wrapper.findAll(".message__duration")).toHaveLength(0);
  });
});
