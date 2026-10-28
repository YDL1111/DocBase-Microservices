import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import SearchPage from "./index.vue";

describe("知识检索前端预览", () => {
  it("明确提示示例范围，并按关键词筛选结果", async () => {
    const wrapper = mount(SearchPage, { global: { plugins: [ElementPlus] } });
    expect(wrapper.text()).toContain("真实检索尚未接入");
    expect(wrapper.findAll('.search-result')).toHaveLength(0);
    await wrapper.find('input').setValue('部署');
    await wrapper.find('form').trigger('submit');
    expect(wrapper.findAll('.search-result')).toHaveLength(1);
    expect(wrapper.find('.search-result').text()).toContain('项目部署指南.pdf');
    await wrapper.find('input').setValue('不存在的内容');
    await wrapper.find('form').trigger('submit');
    expect(wrapper.findAll('.search-result')).toHaveLength(0);
    expect(wrapper.text()).toContain('没有匹配内容');
    wrapper.unmount();
  });
});
