<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { Key, Lock, User } from "@element-plus/icons-vue";
import { getAdminSetupStatus, loginApi, setupFirstAdmin } from "@/api/auth";
import { setLoginResult } from "@/utils/auth";
import { useUserStoreHook } from "@/store/modules/user";
import { message } from "@/utils/message";
import backgroundUrl from "@/assets/login/bg.png";
import illustrationUrl from "@/assets/login/illustration.svg";
import brandUrl from "@/assets/login/avatar.svg";

const REMEMBERED_USERNAME_KEY = "docbase-remembered-username";
const router = useRouter();
const route = useRoute();
const user = useUserStoreHook();
const mounted = ref(true);

const rememberedUsername = localStorage.getItem(REMEMBERED_USERNAME_KEY) || "";
const mode = ref<"login" | "setup">("login");
const setupRequired = ref(false);
const setupEnabled = ref(false);
const setupStatusLoading = ref(true);

const loginFormRef = ref<FormInstance>();
const loading = ref(false);
const rememberUsername = ref(Boolean(rememberedUsername));
const loginForm = reactive({ username: rememberedUsername || "admin", password: "" });

const setupFormRef = ref<FormInstance>();
const setupLoading = ref(false);
const setupForm = reactive({
  setupKey: "",
  username: "admin",
  nickname: "Administrator",
  password: "",
  confirmPassword: ""
});

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

const setupRules: FormRules = {
  setupKey: [
    { required: true, message: "请输入管理员初始化密钥", trigger: "blur" },
    { min: 32, max: 256, message: "初始化密钥长度应为 32～256 位", trigger: "blur" }
  ],
  username: [
    { required: true, message: "请输入管理员账号", trigger: "blur" },
    {
      pattern: /^[A-Za-z][A-Za-z0-9._-]{2,63}$/,
      message: "账号须以字母开头，可使用字母、数字、点、下划线和短横线",
      trigger: "blur"
    }
  ],
  nickname: [
    { required: true, message: "请输入管理员名称", trigger: "blur" },
    { max: 64, message: "管理员名称不能超过 64 位", trigger: "blur" }
  ],
  password: [
    { required: true, message: "请设置管理员密码", trigger: "blur" },
    { min: 8, max: 72, message: "密码长度应为 8～72 个字符", trigger: "blur" },
    { validator: validateBcryptPasswordBytes, trigger: "blur" }
  ],
  confirmPassword: [{ required: true, message: "请再次输入密码", trigger: "blur" }]
};

onMounted(async () => {
  try {
    const status = await getAdminSetupStatus();
    if (!mounted.value) return;
    setupRequired.value = status.required;
    setupEnabled.value = status.enabled;
    if (status.required) mode.value = "setup";
  } catch {
    // IAM 尚未启动时保留正常登录表单，不制造额外的全局报错。
  } finally {
    if (mounted.value) setupStatusLoading.value = false;
  }
});

onBeforeUnmount(() => {
  mounted.value = false;
});

async function handleLogin(formEl: FormInstance | undefined) {
  if (!formEl || loading.value) return;
  loading.value = true;
  try {
    const valid = await formEl.validate().catch(() => false);
    if (!valid || !mounted.value) return;
    const res = await loginApi({
      username: loginForm.username.trim(),
      password: loginForm.password
    });
    if (!mounted.value) return;
    setLoginResult({
      accessToken: res.accessToken,
      refreshToken: res.refreshToken,
      userInfo: res.userInfo
    });
    user.setUserInfo(res.userInfo);
    user.setPermissions(res.permissions);
    if (rememberUsername.value) {
      localStorage.setItem(REMEMBERED_USERNAME_KEY, loginForm.username.trim());
    } else {
      localStorage.removeItem(REMEMBERED_USERNAME_KEY);
    }
    message.success("登录成功");
    router.push((route.query.redirect as string) || "/home");
  } catch {
    // 请求层统一提示，并保留输入供用户修改。
  } finally {
    if (mounted.value) loading.value = false;
  }
}

async function handleSetup(formEl: FormInstance | undefined) {
  if (!formEl || setupLoading.value || !setupEnabled.value) return;
  setupLoading.value = true;
  try {
    const valid = await formEl.validate().catch(() => false);
    if (!valid || !mounted.value) return;
    if (setupForm.password !== setupForm.confirmPassword) {
      message.error("两次输入的密码不一致");
      return;
    }
    const request = {
      setupKey: setupForm.setupKey,
      username: setupForm.username.trim(),
      nickname: setupForm.nickname.trim(),
      password: setupForm.password
    };
    await setupFirstAdmin(request);
    if (!mounted.value) return;
    loginForm.username = request.username;
    loginForm.password = "";
    setupForm.setupKey = "";
    setupForm.password = "";
    setupForm.confirmPassword = "";
    setupRequired.value = false;
    setupEnabled.value = false;
    mode.value = "login";
    message.success("管理员初始化成功，请使用新账号登录");
  } catch {
    // 请求层统一提示；失败时保留表单供修正后重试。
  } finally {
    if (mounted.value) setupLoading.value = false;
  }
}
</script>

<template>
  <main class="login-page" :class="{ 'setup-mode': mode === 'setup' }">
    <img class="login-wave" :src="backgroundUrl" alt="" aria-hidden="true" />
    <section class="login-container" aria-label="DocBase 账号入口">
      <div class="illustration-column" aria-hidden="true">
        <img class="login-illustration" :src="illustrationUrl" alt="" />
      </div>
      <div class="login-column">
        <div class="login-form">
          <img class="brand-logo" :src="brandUrl" alt="DocBase" />
          <h1>DocBase</h1>
          <p class="login-subtitle">企业文档知识库管理平台</p>

          <div v-if="setupRequired" class="mode-switch" aria-label="账号入口选择">
            <button type="button" :class="{ active: mode === 'login' }" @click="mode = 'login'">
              登录
            </button>
            <button type="button" :class="{ active: mode === 'setup' }" @click="mode = 'setup'">
              初始化管理员
            </button>
          </div>

          <el-form
            v-if="mode === 'login'"
            ref="loginFormRef"
            :model="loginForm"
            :rules="loginRules"
            size="large"
            @keyup.enter="handleLogin(loginFormRef)"
          >
            <el-form-item prop="username">
              <el-input v-model="loginForm.username" :prefix-icon="User" placeholder="用户名" autocomplete="username" clearable />
            </el-form-item>
            <el-form-item prop="password">
              <el-input v-model="loginForm.password" :prefix-icon="Lock" type="password" placeholder="密码" show-password autocomplete="current-password" />
            </el-form-item>
            <div class="login-options">
              <el-checkbox v-model="rememberUsername">记住账号</el-checkbox>
              <el-button class="forgot-password" type="primary" link disabled title="请联系系统管理员重置密码">
                忘记密码
              </el-button>
            </div>
            <el-button type="primary" class="login-button" :loading="loading" @click="handleLogin(loginFormRef)">
              登录
            </el-button>
          </el-form>

          <div v-else class="setup-panel">
            <el-alert
              v-if="!setupStatusLoading && !setupEnabled"
              class="setup-alert"
              title="初始化入口尚未启用"
              description="请先在项目 .env 中设置 IAM_ADMIN_SETUP_KEY，并重新构建 IAM 服务。"
              type="warning"
              :closable="false"
              show-icon
            />
            <p class="setup-hint">仅用于首次部署。系统已有有效超级管理员后，此入口会自动关闭。</p>
            <el-form
              ref="setupFormRef"
              :model="setupForm"
              :rules="setupRules"
              size="large"
              @keyup.enter="handleSetup(setupFormRef)"
            >
              <el-form-item prop="setupKey">
                <el-input v-model="setupForm.setupKey" :prefix-icon="Key" type="password" placeholder="管理员初始化密钥" autocomplete="off" show-password :disabled="!setupEnabled || setupLoading" />
              </el-form-item>
              <el-form-item prop="username">
                <el-input v-model="setupForm.username" :prefix-icon="User" placeholder="管理员账号" autocomplete="username" :disabled="!setupEnabled || setupLoading" />
              </el-form-item>
              <el-form-item prop="nickname">
                <el-input v-model="setupForm.nickname" :prefix-icon="User" placeholder="管理员名称" :disabled="!setupEnabled || setupLoading" />
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="setupForm.password" :prefix-icon="Lock" type="password" placeholder="设置密码（8～72 字节）" autocomplete="new-password" show-password :disabled="!setupEnabled || setupLoading" />
              </el-form-item>
              <el-form-item prop="confirmPassword">
                <el-input v-model="setupForm.confirmPassword" :prefix-icon="Lock" type="password" placeholder="再次输入密码" autocomplete="new-password" show-password :disabled="!setupEnabled || setupLoading" />
              </el-form-item>
              <el-button type="primary" class="login-button setup-button" :loading="setupLoading" :disabled="!setupEnabled" @click="handleSetup(setupFormRef)">
                创建首个管理员
              </el-button>
            </el-form>
          </div>
        </div>
      </div>
    </section>
    <footer class="login-footer">Copyright © 2024-2026 DocBase All Rights Reserved.</footer>
  </main>
</template>

<style lang="scss" scoped>
.login-page { position: relative; width: 100%; min-width: 320px; min-height: 100vh; min-height: 100dvh; overflow: hidden; color: #303133; background: #fff; }
.login-wave { position: fixed; bottom: 0; left: 0; z-index: 0; width: auto; height: 100%; pointer-events: none; user-select: none; }
.login-container { position: relative; z-index: 1; display: grid; grid-template-columns: minmax(360px, 1fr) minmax(360px, 1fr); gap: clamp(5rem, 12vw, 18rem); width: 100%; min-height: 100vh; min-height: 100dvh; padding: 0 clamp(2rem, 5vw, 5rem); }
.illustration-column, .login-column { display: flex; align-items: center; }
.illustration-column { justify-content: flex-end; }
.login-illustration { display: block; width: min(500px, 42vw); height: auto; user-select: none; animation: illustration-enter 0.6s ease-out both; }
.login-column { justify-content: flex-start; min-width: 0; text-align: center; }
.login-form { width: 360px; max-width: 100%; animation: form-enter 0.5s 0.08s ease-out both; }
.brand-logo { display: block; width: 66px; height: 66px; margin: 0 auto 12px; }
h1 { margin: 0; color: #606266; font-family: Consolas, Monaco, "Courier New", monospace; font-size: 32px; font-weight: 700; line-height: 1.25; letter-spacing: 0.04em; }
.login-subtitle { margin: 10px 0 28px; color: #909399; font-size: 14px; line-height: 1.6; }
.mode-switch { display: grid; grid-template-columns: 1fr 1fr; margin: -8px 0 20px; border-bottom: 1px solid #ebeef5; }
.mode-switch button { padding: 10px 6px; color: #909399; font: inherit; background: transparent; border: 0; border-bottom: 2px solid transparent; cursor: pointer; }
.mode-switch button.active { color: var(--el-color-primary); font-weight: 600; border-bottom-color: var(--el-color-primary); }
.setup-hint { margin: -4px 0 16px; color: #909399; font-size: 12px; line-height: 1.7; }
.setup-alert { margin: -4px 0 16px; text-align: left; }
.login-options { display: flex; align-items: center; justify-content: space-between; height: 28px; margin: -2px 0 18px; }
.forgot-password.is-disabled { color: #a8abb2; }
.login-button { width: 100%; height: 42px; font-size: 15px; letter-spacing: 0.16em; border-radius: 4px; }
.setup-button { margin-top: 2px; }
:deep(.el-form-item) { margin-bottom: 20px; }
:deep(.el-input__wrapper) { min-height: 44px; border-radius: 4px; box-shadow: 0 0 0 1px #dcdfe6 inset; transition: box-shadow 0.2s ease, transform 0.2s ease; }
:deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #a8abb2 inset; }
:deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px var(--el-color-primary) inset; transform: translateY(-1px); }
.login-footer { position: absolute; right: 24px; bottom: 20px; z-index: 2; color: #a8abb2; font-size: 12px; line-height: 1.5; }
@keyframes illustration-enter { from { opacity: 0; transform: translateX(-18px); } to { opacity: 1; transform: translateX(0); } }
@keyframes form-enter { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 1180px) { .login-container { gap: clamp(3rem, 8vw, 9rem); } .login-form { width: 320px; } }
@media (max-width: 968px) { .login-wave, .illustration-column { display: none; } .login-container { grid-template-columns: 1fr; padding: 0 28px; } .login-column { justify-content: center; } .login-form { width: 100%; max-width: 360px; } .login-footer { right: 20px; left: 20px; text-align: center; } }
@media (max-width: 480px) { .login-container { padding: 0 24px 34px; } .brand-logo { width: 58px; height: 58px; } h1 { font-size: 28px; } .login-subtitle { margin-bottom: 24px; } }
@media (max-height: 820px), (max-width: 968px) and (max-height: 720px) { .login-page.setup-mode { overflow-x: hidden; overflow-y: auto; } .setup-mode .login-container { min-height: auto; padding-top: 28px; padding-bottom: 62px; } .setup-mode .login-column { align-items: flex-start; } .setup-mode .login-footer { position: static; margin: 0 20px 18px; } }
@media (max-width: 968px) and (max-height: 620px) { .login-page { overflow-x: hidden; overflow-y: auto; } .login-container { min-height: auto; padding-top: 28px; padding-bottom: 24px; } .login-column { align-items: flex-start; } .login-footer { position: static; margin: 0 20px 18px; } }
@media (prefers-reduced-motion: reduce) { .login-illustration, .login-form { animation: none; } :deep(.el-input__wrapper) { transition: none; } }
</style>
