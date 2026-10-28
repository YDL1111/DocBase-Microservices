import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { h, type Slots } from "vue";

const mocks = vi.hoisted(() => ({
  loginApi: vi.fn(),
  registerApi: vi.fn(),
  getAdminSetupStatus: vi.fn(),
  getRegistrationStatus: vi.fn(),
  setLoginResult: vi.fn(),
  isAuthenticated: vi.fn(() => false),
  setUserInfo: vi.fn(),
  setPermissions: vi.fn(),
  push: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  validate: vi.fn(async () => true)
}));

vi.mock("@/api/auth", () => ({
  loginApi: mocks.loginApi,
  registerApi: mocks.registerApi,
  getAdminSetupStatus: mocks.getAdminSetupStatus,
  getRegistrationStatus: mocks.getRegistrationStatus
}));
vi.mock("@/utils/auth", () => ({
  setLoginResult: mocks.setLoginResult,
  isAuthenticated: mocks.isAuthenticated
}));
vi.mock("@/store/modules/user", () => ({
  useUserStoreHook: () => ({
    setUserInfo: mocks.setUserInfo,
    setPermissions: mocks.setPermissions
  })
}));
vi.mock("@/utils/message", () => ({
  message: { success: mocks.success, error: mocks.error }
}));
vi.mock("vue-router", () => ({
  useRouter: () => ({ push: mocks.push })
}));

import LandingPage from "./index.vue";

const ElDialog = {
  name: "ElDialog",
  props: ["modelValue"],
  emits: ["update:modelValue"],
  setup(props: any, { slots }: { slots: Slots }) {
    return () =>
      props.modelValue
        ? h("div", { class: "el-dialog-stub" }, [slots.header?.(), slots.default?.()])
        : null;
  }
};
const ElForm = {
  name: "ElForm",
  setup(_props: unknown, { slots, expose }: { slots: Slots; expose: (v: Record<string, unknown>) => void }) {
    expose({ validate: mocks.validate });
    return () => h("form", { class: "el-form" }, slots.default?.());
  }
};
const ElFormItem = {
  name: "ElFormItem",
  setup(_props: unknown, { slots }: { slots: Slots }) {
    return () => h("div", { class: "el-form-item" }, slots.default?.());
  }
};
const ElInput = {
  name: "ElInput",
  props: ["modelValue", "placeholder", "type"],
  emits: ["update:modelValue"],
  template: `<input
    :type="type === 'password' ? 'password' : 'text'"
    :placeholder="placeholder"
    :value="modelValue"
    @input="$emit('update:modelValue', $event.target.value)"
  />`
};
const ElButton = {
  name: "ElButton",
  inheritAttrs: false,
  props: ["disabled", "loading"],
  emits: ["click"],
  template: `<button v-bind="$attrs" type="button" :disabled="disabled || loading" @click="$emit('click')"><slot /></button>`
};
const ElAlert = {
  name: "ElAlert",
  props: ["title"],
  setup(props: any, { slots }: { slots: Slots }) {
    return () => h("div", { class: "el-alert-stub" }, [props.title, slots.default?.()]);
  }
};

function mountLanding() {
  return mount(LandingPage, {
    attachTo: document.body,
    global: {
      stubs: { ElDialog, ElForm, ElFormItem, ElInput, ElButton, ElAlert }
    }
  });
}

describe("landing page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.isAuthenticated.mockReturnValue(false);
    mocks.getRegistrationStatus.mockResolvedValue(true);
    mocks.getAdminSetupStatus.mockResolvedValue({ required: false, enabled: false });
    mocks.validate.mockResolvedValue(true);
  });

  it("渲染品牌名、用途说明与主要行动入口", async () => {
    const wrapper = mountLanding();
    await flushPromises();

    expect(wrapper.find(".hero-lockup").text()).toContain("DocBase");
    expect(wrapper.text()).toContain("团队文档管理与知识检索");
    expect(wrapper.text()).toContain("查看功能");
    expect(wrapper.text()).toContain("进入工作台");
    wrapper.unmount();
  });

  it("首屏产品预览包含示例文档，且为演示数据", () => {
    const wrapper = mountLanding();

    expect(wrapper.text()).toContain("产品设计规范.pdf");
    expect(wrapper.text()).toContain("如何完成项目部署？");
    expect(wrapper.text()).toContain("示例数据");
    wrapper.unmount();
  });

  it("产品功能：切换功能后展示对应界面演示", async () => {
    const wrapper = mountLanding();

    // 默认展示文档归档
    expect(wrapper.text()).toContain("界面交互说明.pdf");
    expect(wrapper.text()).toContain("索引中");

    const tabs = wrapper.findAll(".feat-item");
    const permTab = tabs.find(t => t.text().includes("访问权限"));
    await permTab!.trigger("click");

    expect(wrapper.text()).toContain("可见范围");
    expect(wrapper.text()).toContain("部门 · 产品组");
    expect(wrapper.text()).toContain("仅管理员");
    wrapper.unmount();
  });

  it("知识检索演示：选择问题出现回答，点击引用打开原文并高亮", async () => {
    const wrapper = mountLanding();

    // 初始为待选择状态
    expect(wrapper.find(".q-empty").exists()).toBe(true);

    const chips = wrapper.findAll(".q-chip");
    await chips[0].trigger("click");
    expect(wrapper.find(".q-lead").text()).toContain("完成环境配置与服务初始化");

    // 点击引用来源，打开原文面板并高亮段落
    await wrapper.find(".q-cite").trigger("click");
    await flushPromises();
    expect(wrapper.find(".src-doc-name").text()).toContain("项目部署指南.pdf");
    expect(wrapper.find(".src-body .is-hit").text()).toContain("执行初始化脚本");
    wrapper.unmount();
  });

  it("输入与示例相近的问题也能匹配演示，无关输入给出提示", async () => {
    const wrapper = mountLanding();

    const input = wrapper.find('.q-input input[type="text"]');
    await input.setValue("部署怎么做");
    await wrapper.find(".q-input").trigger("submit");
    expect(wrapper.find(".q-lead").exists()).toBe(true);

    await input.setValue("今天天气怎么样");
    await wrapper.find(".q-input").trigger("submit");
    expect(wrapper.find(".q-hint").exists()).toBe(true);
    wrapper.unmount();
  });

  it("未登录点击登录打开弹窗，登录成功后进入工作台", async () => {
    mocks.loginApi.mockResolvedValue({
      accessToken: "a",
      refreshToken: "r",
      userInfo: { userId: 1, username: "admin" },
      permissions: []
    });
    const wrapper = mountLanding();
    await flushPromises();

    await wrapper.find(".nav-login").trigger("click");
    await flushPromises();
    expect(wrapper.find(".el-dialog-stub").exists()).toBe(true);

    await wrapper.find('input[placeholder="用户名"]').setValue("admin");
    await wrapper.find('input[placeholder="密码"]').setValue("secret123");
    await wrapper.find(".auth-submit").trigger("click");
    await flushPromises();

    expect(mocks.loginApi).toHaveBeenCalledWith({ username: "admin", password: "secret123" });
    expect(mocks.setLoginResult).toHaveBeenCalled();
    expect(mocks.push).toHaveBeenCalledWith("/home");
    wrapper.unmount();
  });

  it("已登录点击进入工作台直接跳转，不打开弹窗", async () => {
    mocks.isAuthenticated.mockReturnValue(true);
    const wrapper = mountLanding();
    await flushPromises();

    await wrapper.find(".btn-nav").trigger("click");
    await flushPromises();

    expect(mocks.push).toHaveBeenCalledWith("/home");
    expect(wrapper.find(".el-dialog-stub").exists()).toBe(false);
    wrapper.unmount();
  });
  it("预览菜单切换内容，登录后进入选择的实际页面", async () => {
    mocks.loginApi.mockResolvedValue({ accessToken: 'a', refreshToken: 'r', userInfo: { userId: 1 }, permissions: [] });
    const wrapper = mountLanding();
    await wrapper.findAll('.doc-side-item')[3].trigger('click');
    expect(wrapper.find('.doc-main').text()).toContain('已选择团队知识库');
    await wrapper.find('.preview-open').trigger('click');
    await flushPromises();
    await wrapper.find('input[placeholder="用户名"]').setValue('tester');
    await wrapper.find('input[placeholder="密码"]').setValue('secret123');
    await wrapper.find('.auth-submit').trigger('click');
    await flushPromises();
    expect(mocks.push).toHaveBeenCalledWith('/ai/chat');
    wrapper.unmount();
  });

  it("注册状态请求失败时可重试，关闭注册时不提供提交表单", async () => {
    mocks.getRegistrationStatus.mockRejectedValueOnce(new Error('offline'));
    const wrapper = mountLanding();
    await wrapper.find('.nav-login').trigger('click');
    await flushPromises();
    await wrapper.findAll('.auth-switch button')[1].trigger('click');
    expect(wrapper.find('.registration-status').text()).toContain('无法连接');
    expect(wrapper.find('input[placeholder="昵称"]').exists()).toBe(false);
    mocks.getRegistrationStatus.mockResolvedValueOnce(false);
    await wrapper.find('.registration-status button').trigger('click');
    await flushPromises();
    expect(wrapper.find('.registration-status').text()).toContain('暂未开放');
    expect(mocks.registerApi).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("注册开放时展示完整注册表单", async () => {
    const wrapper = mountLanding();
    await wrapper.find('.nav-login').trigger('click');
    await flushPromises();
    await wrapper.findAll('.auth-switch button')[1].trigger('click');
    expect(wrapper.find('input[placeholder="昵称"]').exists()).toBe(true);
    expect(wrapper.find('input[placeholder="再次输入密码"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("首屏初次加载和菜单切换均不显示已移除的浮层", async () => {
    const wrapper = mountLanding();
    expect(wrapper.find('.qa-card').exists()).toBe(false);
    await wrapper.findAll('.doc-side-item')[1].trigger('click');
    expect(wrapper.find('.qa-card').exists()).toBe(false);
    expect(wrapper.find('.stage-badge').exists()).toBe(false);
    expect(wrapper.find('.showcase').classes()).toContain('is-exploring');
    await wrapper.findAll('.doc-side-item')[3].trigger('click');
    await wrapper.findAll('.doc-side-item')[1].trigger('click');
    expect(wrapper.find('.qa-card').exists()).toBe(false);
    expect(wrapper.find('.doc-main').text()).toContain('团队知识库');
    wrapper.unmount();
  });

});
