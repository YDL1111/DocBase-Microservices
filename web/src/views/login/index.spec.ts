import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { h, type Slots } from "vue";

const mocks = vi.hoisted(() => ({
  loginApi: vi.fn(),
  getAdminSetupStatus: vi.fn(),
  getRegistrationStatus: vi.fn(),
  registerApi: vi.fn(),
  setupFirstAdmin: vi.fn(),
  setLoginResult: vi.fn(),
  setUserInfo: vi.fn(),
  setPermissions: vi.fn(),
  push: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  validate: vi.fn()
}));

vi.mock("@/api/auth", () => ({
  loginApi: mocks.loginApi,
  getAdminSetupStatus: mocks.getAdminSetupStatus,
  getRegistrationStatus: mocks.getRegistrationStatus,
  registerApi: mocks.registerApi,
  setupFirstAdmin: mocks.setupFirstAdmin
}));
vi.mock("@/utils/auth", () => ({ setLoginResult: mocks.setLoginResult }));
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
  useRouter: () => ({ push: mocks.push }),
  useRoute: () => ({ query: { redirect: "/knowledge" } })
}));

import LoginPage from "./index.vue";

const ElForm = {
  name: "ElForm",
  setup(_props: unknown, { slots, expose }: { slots: Slots; expose: (value: Record<string, unknown>) => void }) {
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
  props: ["modelValue", "placeholder", "type", "disabled"],
  emits: ["update:modelValue"],
  template: `<input
    :type="type === 'password' ? 'password' : 'text'"
    :placeholder="placeholder"
    :value="modelValue"
    :disabled="disabled"
    @input="$emit('update:modelValue', $event.target.value)"
  />`
};
const ElCheckbox = {
  name: "ElCheckbox",
  props: ["modelValue"],
  emits: ["update:modelValue"],
  template: `<label><input class="remember-checkbox" type="checkbox" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)" /><slot /></label>`
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
  props: ["title", "description"],
  template: `<div class="el-alert"><strong>{{ title }}</strong><span>{{ description }}</span></div>`
};

function mountLogin() {
  return mount(LoginPage, {
    global: {
      components: { ElForm, ElFormItem, ElInput, ElCheckbox, ElButton, ElAlert }
    }
  });
}

describe("DocBase 登录与首次管理员初始化", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mocks.getAdminSetupStatus.mockResolvedValue({ required: false, enabled: false });
    mocks.getRegistrationStatus.mockResolvedValue(false);
    mocks.validate.mockResolvedValue(true);
  });

  it("保留旧版波浪背景、知识协作插画和登录表单", async () => {
    const wrapper = mountLogin();
    await flushPromises();
    expect(wrapper.find(".login-wave").exists()).toBe(true);
    expect(wrapper.find(".login-illustration").exists()).toBe(true);
    expect(wrapper.find(".brand-logo").exists()).toBe(true);
    expect(wrapper.get("h1").text()).toBe("DocBase");
    expect(wrapper.text()).toContain("企业文档知识库管理平台");
    expect(wrapper.findAll("input")).toHaveLength(3);
    expect(wrapper.text()).not.toContain("DocBase Microservices");
  });

  it("通过现有登录 API 建立登录态并跳转原目标", async () => {
    mocks.loginApi.mockResolvedValue({
      accessToken: "access-token",
      refreshToken: "refresh-token",
      userInfo: { userId: 7, username: "alice", nickname: "Alice", email: "", phoneNumber: "", admin: false },
      permissions: ["knowledge:base:list"]
    });
    const wrapper = mountLogin();
    await flushPromises();
    await wrapper.get('input[placeholder="用户名"]').setValue("alice");
    await wrapper.get('input[placeholder="密码"]').setValue("password123");
    await wrapper.get(".remember-checkbox").setValue(true);
    await wrapper.get(".login-button").trigger("click");
    await flushPromises();
    expect(mocks.loginApi).toHaveBeenCalledWith({ username: "alice", password: "password123" });
    expect(mocks.setLoginResult).toHaveBeenCalledTimes(1);
    expect(mocks.setPermissions).toHaveBeenCalledWith(["knowledge:base:list"]);
    expect(localStorage.getItem("docbase-remembered-username")).toBe("alice");
    expect(mocks.push).toHaveBeenCalledWith("/knowledge");
  });

  it("仅在后端要求时展示受密钥保护的初始化表单并创建管理员", async () => {
    mocks.getAdminSetupStatus.mockResolvedValue({ required: true, enabled: true });
    mocks.setupFirstAdmin.mockResolvedValue(11);
    const wrapper = mountLogin();
    await flushPromises();
    expect(wrapper.text()).toContain("初始化管理员");
    expect(wrapper.text()).not.toContain("注册账号");
    await wrapper.get('input[placeholder="管理员初始化密钥"]').setValue("operator-setup-key-at-least-32-chars");
    await wrapper.get('input[placeholder="管理员账号"]').setValue("admin");
    await wrapper.get('input[placeholder="管理员名称"]').setValue("系统管理员");
    await wrapper.get('input[placeholder="设置密码（8～72 字节）"]').setValue("StrongPass!123");
    await wrapper.get('input[placeholder="再次输入密码"]').setValue("StrongPass!123");
    await wrapper.get(".setup-button").trigger("click");
    await flushPromises();
    expect(mocks.setupFirstAdmin).toHaveBeenCalledWith({
      setupKey: "operator-setup-key-at-least-32-chars",
      username: "admin",
      nickname: "系统管理员",
      password: "StrongPass!123"
    });
    expect(mocks.success).toHaveBeenCalledWith("管理员初始化成功，请使用新账号登录");
    expect(wrapper.text()).not.toContain("初始化管理员");
    expect((wrapper.get('input[placeholder="用户名"]').element as HTMLInputElement).value)
      .toBe("admin");
  });

  it("未配置操作密钥时明确提示且禁止初始化", async () => {
    mocks.getAdminSetupStatus.mockResolvedValue({ required: true, enabled: false });
    const wrapper = mountLogin();
    await flushPromises();
    expect(wrapper.text()).toContain("初始化入口尚未启用");
    expect(wrapper.text()).toContain("IAM_ADMIN_SETUP_KEY");
    expect(wrapper.get(".setup-button").attributes("disabled")).toBeDefined();
  });

  it("两次密码不一致时不发送初始化请求", async () => {
    mocks.getAdminSetupStatus.mockResolvedValue({ required: true, enabled: true });
    const wrapper = mountLogin();
    await flushPromises();
    await wrapper.get('input[placeholder="管理员初始化密钥"]').setValue("operator-setup-key-at-least-32-chars");
    await wrapper.get('input[placeholder="设置密码（8～72 字节）"]').setValue("StrongPass!123");
    await wrapper.get('input[placeholder="再次输入密码"]').setValue("Different!456");
    await wrapper.get(".setup-button").trigger("click");
    await flushPromises();
    expect(mocks.setupFirstAdmin).not.toHaveBeenCalled();
    expect(mocks.error).toHaveBeenCalledWith("两次输入的密码不一致");
  });

  it("登录校验未完成时快速双击只提交一次", async () => {
    let resolveValidation!: (valid: boolean) => void;
    mocks.validate.mockImplementationOnce(() => new Promise<boolean>(resolve => {
      resolveValidation = resolve;
    }));
    mocks.loginApi.mockResolvedValue({
      accessToken: "access-token",
      refreshToken: "refresh-token",
      userInfo: { userId: 7, username: "admin", nickname: "Admin", email: "", phoneNumber: "", admin: true },
      permissions: ["admin:all"]
    });
    const wrapper = mountLogin();
    await flushPromises();
    await wrapper.get('input[placeholder="密码"]').setValue("password123");
    const button = wrapper.get(".login-button");
    void button.trigger("click");
    void button.trigger("click");
    expect(mocks.validate).toHaveBeenCalledTimes(1);
    resolveValidation(true);
    await flushPromises();
    expect(mocks.loginApi).toHaveBeenCalledTimes(1);
  });

  it("初始化校验未完成时快速双击只创建一次", async () => {
    mocks.getAdminSetupStatus.mockResolvedValue({ required: true, enabled: true });
    let resolveValidation!: (valid: boolean) => void;
    mocks.validate.mockImplementationOnce(() => new Promise<boolean>(resolve => {
      resolveValidation = resolve;
    }));
    mocks.setupFirstAdmin.mockResolvedValue(11);
    const wrapper = mountLogin();
    await flushPromises();
    await wrapper.get('input[placeholder="管理员初始化密钥"]').setValue("operator-setup-key-at-least-32-chars");
    await wrapper.get('input[placeholder="设置密码（8～72 字节）"]').setValue("StrongPass!123");
    await wrapper.get('input[placeholder="再次输入密码"]').setValue("StrongPass!123");
    const button = wrapper.get(".setup-button");
    void button.trigger("click");
    void button.trigger("click");
    expect(mocks.validate).toHaveBeenCalledTimes(1);
    resolveValidation(true);
    await flushPromises();
    expect(mocks.setupFirstAdmin).toHaveBeenCalledTimes(1);
  });

  it("开放注册时创建最小权限账号并回填登录用户名", async () => {
    mocks.getRegistrationStatus.mockResolvedValue(true);
    mocks.registerApi.mockResolvedValue(21);
    const wrapper = mountLogin();
    await flushPromises();
    await wrapper.findAll(".mode-switch button").find(button => button.text() === "注册")!.trigger("click");
    await wrapper.get('input[placeholder="用户名"]').setValue("alice");
    await wrapper.get('input[placeholder="昵称"]').setValue("Alice");
    await wrapper.get('input[placeholder="邮箱（选填）"]').setValue("alice@example.com");
    await wrapper.get('input[placeholder="密码（8～72 字节）"]').setValue("password123");
    await wrapper.get('input[placeholder="再次输入密码"]').setValue("password123");
    await wrapper.get(".login-button").trigger("click");
    await flushPromises();
    expect(mocks.registerApi).toHaveBeenCalledWith({
      username: "alice", nickname: "Alice", email: "alice@example.com", password: "password123"
    });
    expect(mocks.registerApi.mock.calls[0][0]).not.toHaveProperty("roleIds");
    expect(wrapper.get('input[placeholder="用户名"]')).toBeTruthy();
  });
});
