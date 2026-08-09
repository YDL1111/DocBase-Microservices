import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ChatComposer from "./chat-composer.vue";

const ElInput = { props: ["modelValue"], emits: ["update:modelValue", "keydown"], template: "<textarea :value=\"modelValue\" @input=\"$emit('update:modelValue', $event.target.value)\" @keydown=\"$emit('keydown', $event)\" />" };
const ElButton = { emits: ["click"], template: "<button @click=\"$emit('click')\"><slot /></button>" };
const mountComposer = (props: Record<string, unknown>) => mount(ChatComposer, { props: { modelValue: "question", streaming: false, canSend: true, ...props }, global: { stubs: { ElInput, ElButton } } });

describe("chat composer", () => {
  it("sends on Enter but preserves Shift+Enter for a newline", async () => {
    const wrapper = mountComposer({});
    await wrapper.find("textarea").trigger("keydown", { key: "Enter", shiftKey: false });
    expect(wrapper.emitted("send")).toHaveLength(1);
    await wrapper.find("textarea").trigger("keydown", { key: "Enter", shiftKey: true });
    expect(wrapper.emitted("send")).toHaveLength(1);
  });

  it("does not send blank text and turns the action into stop while streaming", async () => {
    const blank = mountComposer({ modelValue: "   " });
    await blank.find("textarea").trigger("keydown", { key: "Enter" });
    expect(blank.emitted("send")).toBeUndefined();
    const streaming = mountComposer({ streaming: true });
    await streaming.find("button").trigger("click");
    expect(streaming.emitted("stop")).toHaveLength(1);
  });

  it("keeps a typed question when sending is temporarily unavailable", async () => {
    const wrapper = mountComposer({ canSend: false, modelValue: "retry after drain" });
    await wrapper.find("textarea").trigger("keydown", { key: "Enter" });
    await wrapper.find("button").trigger("click");
    expect(wrapper.emitted("send")).toBeUndefined();
    expect((wrapper.find("textarea").element as HTMLTextAreaElement).value).toBe("retry after drain");
  });
});
