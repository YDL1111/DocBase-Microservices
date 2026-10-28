<script setup lang="ts">
/**
 * 品牌首页登录/注册弹窗。
 *
 * 复用与登录页一致的接口与校验规则：
 *  - 登录：loginApi + setLoginResult + user store，成功后 emit success 由首页跳转工作台；
 *  - 注册：registerApi，是否开放由 getRegistrationStatus 决定（探测失败时隐藏注册入口）；
 *  - 首次部署未初始化管理员时，引导前往独立登录页完成初始化。
 *
 * 可访问性：el-dialog 自带焦点圈定与 Esc 关闭；打开时将焦点移到首个输入框。
 */
import { nextTick, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import type { FormInstance, FormRules, InputInstance } from "element-plus";
import { Lock, User } from "@element-plus/icons-vue";
import {
  getAdminSetupStatus,
  getRegistrationStatus,
  loginApi,
  registerApi
} from "@/api/auth";
import { setLoginResult } from "@/utils/auth";
import { useUserStoreHook } from "@/store/modules/user";
import { message } from "@/utils/message";

const props = withDefaults(
  defineProps<{ initialMode?: "login" | "register" }>(),
  { initialMode: "login" }
);
const emit = defineEmits<{ success: [] }>();

const visible = defineModel<boolean>({ required: true });

const router = useRouter();
const user = useUserStoreHook();
const mode = ref<"login" | "register">("login");
const registrationEnabled = ref(false);
const registrationState = ref<'loading' | 'ready' | 'closed' | 'error'>('loading');
let statusRequest = 0;
async function loadRegistrationStatus() {
  const request = ++statusRequest;
  registrationState.value = 'loading';
  registrationEnabled.value = false;
  try {
    const enabled = await getRegistrationStatus();
    if (request !== statusRequest) return;
    registrationEnabled.value = enabled;
    registrationState.value = enabled ? 'ready' : 'closed';
  } catch {
    if (request === statusRequest) registrationState.value = 'error';
  }
}
const setupRequired = ref(false);

const loginFormRef = ref<FormInstance>();
const loginLoading = ref(false);
const loginForm = reactive({ username: "", password: "" });
const loginInputRef = ref<InputInstance>();

const registerFormRef = ref<FormInstance>();
const registerLoading = ref(false);
const registerForm = reactive({
  username: "",
  nickname: "",
  email: "",
  password: "",
  confirmPassword: ""
});
const registerInputRef = ref<InputInstance>();

const loginRules: FormRules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [
    { required: true, message: "请输入密码", trigger: "blur" },
    { min: 6, message: "密码至少 6 位", trigger: "blur" }
  ]
};

function validateBcryptPasswordBytes(
  _rule: unknown,
  value: unknown,
  callback: (error?: Error) => void
) {
  if (typeof value === "string" && new TextEncoder().encode(value).length > 72) {
    callback(new Error("密码的 UTF-8 编码不能超过 72 字节（中文通常占 3 字节）"));
    return;
  }
  callback();
}

const registerRules: FormRules = {
  username: [
    { required: true, message: "请输入用户名", trigger: "blur" },
    {
      pattern: /^[A-Za-z][A-Za-z0-9._-]{2,63}$/,
      message: "账号须以字母开头，可使用字母、数字、点、下划线和短横线",
      trigger: "blur"
    }
  ],
  nickname: [
    { required: true, message: "请输入昵称", trigger: "blur" },
    { max: 64, message: "昵称不能超过 64 位", trigger: "blur" }
  ],
  email: [{ type: "email", message: "请输入正确的邮箱地址", trigger: "blur" }],
  password: [
    { required: true, message: "请设置密码", trigger: "blur" },
    { min: 8, max: 72, message: "密码长度应为 8～72 个字符", trigger: "blur" },
    { validator: validateBcryptPasswordBytes, trigger: "blur" }
  ],
  confirmPassword: [{ required: true, message: "请再次输入密码", trigger: "blur" }]
};

/** 打开时同步初始模式并探测注册/初始化状态，聚焦首个输入框 */
watch(visible, async open => {
  if (!visible.value) return;
  mode.value = props.initialMode;
  try {
    const [, setupStatus] = await Promise.all([
      loadRegistrationStatus(),
      getAdminSetupStatus().catch(() => ({ required: false, enabled: false }))
    ]);
    if (!visible.value) return;
    setupRequired.value = setupStatus.required;
  } catch {
    // 探测失败时保留登录表单，不制造额外报错
  }
  await nextTick();
  const firstInput = mode.value === "login" ? loginInputRef : registerInputRef;
  firstInput.value?.focus?.();
});

async function handleLogin() {
  const formEl = loginFormRef.value;
  if (!formEl || loginLoading.value) return;
  loginLoading.value = true;
  try {
    const valid = await formEl.validate().catch(() => false);
    if (!valid || !visible.value) return;
    const res = await loginApi({
      username: loginForm.username.trim(),
      password: loginForm.password
    });
    if (!visible.value) return;
    setLoginResult({
      accessToken: res.accessToken,
      refreshToken: res.refreshToken,
      userInfo: res.userInfo
    });
    user.setUserInfo(res.userInfo);
    user.setPermissions(res.permissions);
    loginForm.password = "";
    message.success("登录成功");
    emit("success");
  } catch {
    // 请求层统一提示，并保留输入供用户修改
  } finally {
    if (visible.value) loginLoading.value = false;
  }
}

async function handleRegister() {
  const formEl = registerFormRef.value;
  if (!formEl || registerLoading.value || !registrationEnabled.value) return;
  registerLoading.value = true;
  try {
    const valid = await formEl.validate().catch(() => false);
    if (!valid || !visible.value) return;
    if (registerForm.password !== registerForm.confirmPassword) {
      message.error("两次输入的密码不一致");
      return;
    }
    const username = registerForm.username.trim();
    await registerApi({
      username,
      nickname: registerForm.nickname.trim(),
      email: registerForm.email.trim() || undefined,
      password: registerForm.password
    });
    if (!visible.value) return;
    loginForm.username = username;
    loginForm.password = "";
    registerForm.password = "";
    registerForm.confirmPassword = "";
    mode.value = "login";
    message.success("注册成功，请登录；组织归属可由管理员稍后分配");
  } catch {
    // 请求层统一提示，失败保留输入
  } finally {
    if (visible.value) registerLoading.value = false;
  }
}

/** 首次部署：引导到独立登录页完成管理员初始化 */
function goSetup() {
  visible.value = false;
  router.push("/login");
}
</script>

<template>
  <el-dialog
    v-model="visible"
    class="auth-dialog"
    width="420px"
    align-center
    :show-close="true"
    :close-on-press-escape="true"
    :close-on-click-modal="false"
    append-to-body
    aria-label="登录或注册 DocBase"
  >
    <template #header>
      <div class="auth-head">
        <svg class="auth-mark" viewBox="0 0 32 32" aria-hidden="true">
          <rect width="32" height="32" rx="7" fill="#175e4c" />
          <path d="M9 8.5h9.2c2.6 0 4.8 2.1 4.8 4.8V24h-9.2a4.8 4.8 0 0 1-4.8-4.8V8.5Z" fill="#f3efe2" />
          <path d="M13 13.2h6M13 16.6h6M13 20h4" stroke="#175e4c" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <div class="auth-head-copy">
          <h2>{{ mode === "login" ? "登录 DocBase" : "创建账号" }}</h2>
          <p>{{ mode === "login" ? "让每一份文档，成为可用的知识。" : "注册后可使用公开知识库与 AI 对话。" }}</p>
        </div>
      </div>
    </template>

    <el-alert
      v-if="setupRequired"
      class="auth-setup-alert"
      title="系统尚未初始化管理员"
      description="首次部署需要先在登录页完成管理员初始化。"
      type="warning"
      :closable="false"
      show-icon
    >
      <el-button type="warning" plain size="small" @click="goSetup">前往初始化</el-button>
    </el-alert>

    <div class="auth-switch" role="tablist" aria-label="登录或注册">
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'login'"
        :class="{ active: mode === 'login' }"
        @click="mode = 'login'"
      >
        登录
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'register'"
        :class="{ active: mode === 'register' }"
        @click="mode = 'register'"
      >
        注册
      </button>
    </div>

    <el-form
      v-if="mode === 'login'"
      ref="loginFormRef"
      :model="loginForm"
      :rules="loginRules"
      size="large"
      @keyup.enter="handleLogin"
    >
      <el-form-item prop="username">
        <el-input
          ref="loginInputRef"
          v-model="loginForm.username"
          :prefix-icon="User"
          placeholder="用户名"
          autocomplete="username"
          clearable
        />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="loginForm.password"
          :prefix-icon="Lock"
          type="password"
          placeholder="密码"
          show-password
          autocomplete="current-password"
        />
      </el-form-item>
      <el-button
        type="primary"
        class="auth-submit"
        :loading="loginLoading"
        @click="handleLogin"
      >
        登录
      </el-button>
    </el-form>

    <div v-else-if="registrationState !== 'ready'" class="registration-status" role="status">
      <p v-if="registrationState === 'loading'">正在确认注册是否开放…</p>
      <p v-else-if="registrationState === 'closed'">当前暂未开放自主注册，请联系管理员创建账号。</p>
      <template v-else><p>暂时无法连接注册服务，请稍后重试。</p><el-button @click="loadRegistrationStatus">重新检查</el-button></template>
    </div>
    <el-form
      v-else
      ref="registerFormRef"
      :model="registerForm"
      :rules="registerRules"
      size="large"
      @keyup.enter="handleRegister"
    >
      <el-form-item prop="username">
        <el-input
          ref="registerInputRef"
          v-model="registerForm.username"
          :prefix-icon="User"
          placeholder="用户名"
          autocomplete="username"
        />
      </el-form-item>
      <el-form-item prop="nickname">
        <el-input v-model="registerForm.nickname" :prefix-icon="User" placeholder="昵称" />
      </el-form-item>
      <el-form-item prop="email">
        <el-input v-model="registerForm.email" :prefix-icon="User" placeholder="邮箱（选填）" autocomplete="email" />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="registerForm.password"
          :prefix-icon="Lock"
          type="password"
          placeholder="密码（8～72 字节）"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>
      <el-form-item prop="confirmPassword">
        <el-input
          v-model="registerForm.confirmPassword"
          :prefix-icon="Lock"
          type="password"
          placeholder="再次输入密码"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>
      <el-button
        type="primary"
        class="auth-submit"
        :loading="registerLoading"
        @click="handleRegister"
      >
        创建账号
      </el-button>
    </el-form>

    <p class="auth-foot">登录即表示同意团队的使用规范与数据权限约定。</p>
  </el-dialog>
</template>

<style lang="scss" scoped>
.registration-status { padding: 20px 0; color: var(--docbase-muted); line-height: 1.8; text-align: center; }
.auth-head { display: flex; align-items: center; gap: 13px; }
.auth-mark { flex: none; width: 38px; height: 38px; border-radius: 9px; }
.auth-head-copy h2 { margin: 0; color: var(--docbase-ink); font-size: 19px; font-weight: 700; letter-spacing: -0.3px; }
.auth-head-copy p { margin: 3px 0 0; color: var(--docbase-muted); font-size: 12px; }
.auth-setup-alert { margin-bottom: 18px; }
.auth-setup-alert :deep(.el-button) { margin-top: 10px; }

.auth-switch { display: grid; grid-template-columns: repeat(2, 1fr); gap: 5px; margin-bottom: 22px; padding: 4px; background: var(--docbase-surface-soft); border: 1px solid var(--docbase-line-soft); border-radius: 10px; }
.auth-switch button { padding: 9px 6px; color: var(--docbase-muted); font: inherit; font-size: 13px; background: transparent; border: 0; border-radius: 7px; cursor: pointer; transition: background-color .18s ease, color .18s ease; }
.auth-switch button.active { color: var(--docbase-ink); font-weight: 650; background: var(--docbase-surface); box-shadow: 0 1px 4px rgba(36, 33, 26, 0.12); }

.auth-submit { width: 100%; height: 44px; font-size: 14px; letter-spacing: .08em; border-radius: 8px; }
.auth-foot { margin: 18px 0 0; color: var(--docbase-subtle); font-size: 11px; line-height: 1.7; text-align: center; }

:deep(.el-form-item) { margin-bottom: 18px; }
:deep(.el-input__wrapper) { min-height: 44px; padding: 1px 14px; background: #fff; border-radius: 8px; }
</style>

<style>
/* 弹窗品牌化（非 scoped：el-dialog 渲染在 body 下） */
.auth-dialog {
  --el-color-primary: #175e4c;
  font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  border: 1px solid #dde3d8;
  background: #fbfaf5;
  border-radius: 18px;
  box-shadow: 0 24px 80px rgba(15, 41, 31, .22);
}
.auth-dialog .el-input__wrapper { box-shadow: 0 0 0 1px #d8ded3 inset; transition: box-shadow .18s ease; }
.auth-dialog .el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1px #175e4c inset, 0 0 0 3px rgba(23, 94, 76, .12);
}
.auth-dialog .el-input__inner:focus-visible { outline: none; }
.auth-dialog button:focus-visible { outline: 2px solid #175e4c; outline-offset: 3px; }
.auth-dialog .el-form-item.is-error .el-input__wrapper { box-shadow: 0 0 0 1px var(--el-color-danger) inset; }
.auth-dialog .auth-submit { box-shadow: 0 2px 4px rgba(16, 72, 58, .10); }
.auth-dialog .el-dialog__header { padding: 22px 24px 16px; }
.auth-dialog .el-dialog__body { padding: 4px 24px 22px; }
.auth-dialog .el-dialog__headerbtn { top: 20px; right: 20px; }
.auth-dialog .el-button--primary:not(.is-link):not(.is-text):not(.is-plain) {
  --el-button-bg-color: #175e4c;
  --el-button-border-color: #175e4c;
  --el-button-hover-bg-color: #10483a;
  --el-button-hover-border-color: #10483a;
  --el-button-active-bg-color: #0d3d31;
  --el-button-active-border-color: #0d3d31;
}
/* 移动端接近全屏，保证表单可操作 */
@media (max-width: 560px) {
  .auth-dialog.el-dialog {
    width: calc(100% - 24px) !important;
    margin-top: 6vh;
    border-radius: 14px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .auth-switch button { transition: none; }
}
</style>
