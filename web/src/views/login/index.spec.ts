import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { h, type Slots } from "vue";

const {
  loginApi,
  setLoginResult,
  setUserInfo,
  setPermissions,
  push,
  success
} = vi.hoisted(() => ({
  loginApi: vi.fn(),
  setLoginResult: vi.fn(),
  setUserInfo: vi.fn(),
  setPermissions: vi.fn(),
  push: vi.fn(),
  success: vi.fn()
}));

vi.mock("@/api/auth", () => ({ loginApi }));
vi.mock("@/utils/auth", () => ({ setLoginResult }));
vi.mock("@/store/modules/user", () => ({
  useUserStoreHook: () => ({ setUserInfo, setPermissions })
}));
vi.mock("@/utils/message", () => ({
  message: { success }
}));
vi.mock("vue-router", () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ query: { redirect: "/knowledge" } })
}));

import LoginPage from "./index.vue";

const ElForm = {
  name: "ElForm",
  setup(_props: unknown, { slots, expose }: { slots: Slots; expose: (value: Record<string, unknown>) => void }) {
    expose({ validate: () => Promise.resolve(true) });
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
  template: `
    <input
      :type="type === 'password' ? 'password' : 'text'"
      :placeholder="placeholder"
      :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)"
    />
  `
};

const ElCheckbox = {
  name: "ElCheckbox",
  props: ["modelValue"],
  emits: ["update:modelValue"],
  template: `
    <label>
      <input
        class="remember-checkbox"
        type="checkbox"
        :checked="modelValue"
        @change="$emit('update:modelValue', $event.target.checked)"
      />
      <slot />
    </label>
  `
};

const ElButton = {
  name: "ElButton",
  inheritAttrs: false,
  props: ["disabled", "loading"],
  emits: ["click"],
  template: `
    <button
      v-bind="$attrs"
      type="button"
      :disabled="disabled || loading"
      @click="$emit('click')"
    >
      <slot />
    </button>
  `
};

function mountLogin() {
  return mount(LoginPage, {
    global: {
      components: { ElForm, ElFormItem, ElInput, ElCheckbox, ElButton }
    }
  });
}

describe("旧版 DocBase 登录视觉与微服务登录契约", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it("恢复波形背景、知识协作插画与开放式登录表单", () => {
    const wrapper = mountLogin();

    expect(wrapper.find(".login-wave").exists()).toBe(true);
    expect(wrapper.find(".login-illustration").exists()).toBe(true);
    expect(wrapper.find(".brand-logo").exists()).toBe(true);
    expect(wrapper.get("h1").text()).toBe("DocBase");
    expect(wrapper.text()).toContain("企业文档知识库管理平台");
    expect(wrapper.findAll("input")).toHaveLength(3);
    expect(wrapper.text()).not.toContain("DocBase Microservices");
    expect(wrapper.text()).not.toContain("Gateway");
  });

  it("仍通过现有登录 API 建立微服务登录态并跳转原目标", async () => {
    loginApi.mockResolvedValueOnce({
      accessToken: "access-token",
      refreshToken: "refresh-token",
      userInfo: {
        userId: 7,
        username: "alice",
        nickname: "Alice",
        email: "",
        phoneNumber: "",
        admin: false
      },
      permissions: ["knowledge:base:list"]
    });
    const wrapper = mountLogin();

    await wrapper.get('input[placeholder="用户名"]').setValue("alice");
    await wrapper.get('input[placeholder="密码"]').setValue("password123");
    await wrapper.get(".remember-checkbox").setValue(true);
    await wrapper.get(".login-button").trigger("click");
    await flushPromises();

    expect(loginApi).toHaveBeenCalledWith({
      username: "alice",
      password: "password123"
    });
    expect(setLoginResult).toHaveBeenCalledTimes(1);
    expect(setUserInfo).toHaveBeenCalledWith(expect.objectContaining({ userId: 7 }));
    expect(setPermissions).toHaveBeenCalledWith(["knowledge:base:list"]);
    expect(localStorage.getItem("docbase-remembered-username")).toBe("alice");
    expect(success).toHaveBeenCalledWith("登录成功");
    expect(push).toHaveBeenCalledWith("/knowledge");
  });

  it("不勾选记住账号时不会持久化用户名", async () => {
    localStorage.setItem("docbase-remembered-username", "old-user");
    loginApi.mockResolvedValueOnce({
      accessToken: "access-token",
      refreshToken: "refresh-token",
      userInfo: {
        userId: 1,
        username: "admin",
        nickname: "管理员",
        email: "",
        phoneNumber: "",
        admin: true
      },
      permissions: []
    });
    const wrapper = mountLogin();

    await wrapper.get(".remember-checkbox").setValue(false);
    await wrapper.get('input[placeholder="密码"]').setValue("password123");
    await wrapper.get(".login-button").trigger("click");
    await flushPromises();

    expect(localStorage.getItem("docbase-remembered-username")).toBeNull();
  });
});
