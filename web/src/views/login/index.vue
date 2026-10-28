<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { Key, Lock, User } from "@element-plus/icons-vue";
import { getAdminSetupStatus, getRegistrationStatus, loginApi, registerApi, setupFirstAdmin } from "@/api/auth";
import { setLoginResult } from "@/utils/auth";
import { useUserStoreHook } from "@/store/modules/user";
import { message } from "@/utils/message";
import backgroundUrl from "@/assets/login/bg.png";
import brandUrl from "@/assets/login/avatar.svg";

const REMEMBERED_USERNAME_KEY = "docbase-remembered-username";
const router = useRouter();
const route = useRoute();
const user = useUserStoreHook();
const mounted = ref(true);

const rememberedUsername = localStorage.getItem(REMEMBERED_USERNAME_KEY) || "";
const mode = ref<"login" | "register" | "setup">("login");
const setupRequired = ref(false);
const setupEnabled = ref(false);
const setupStatusLoading = ref(true);
const registrationEnabled = ref(false);

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

const registerFormRef = ref<FormInstance>();
const registerLoading = ref(false);
const registerForm = reactive({
  username: "",
  nickname: "",
  email: "",
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

const registerRules: FormRules = {
  username: setupRules.username,
  nickname: setupRules.nickname,
  email: [{ type: "email", message: "请输入正确的邮箱地址", trigger: "blur" }],
  password: setupRules.password,
  confirmPassword: [{ required: true, message: "请再次输入密码", trigger: "blur" }]
};

/* 鼠标视差：星图背景层随指针轻微漂移（触屏与减弱动效时自动关闭） */
const parallax = reactive({ x: 0, y: 0 });
let parallaxEnabled = false;

function handleMouseMove(event: MouseEvent) {
  parallax.x = event.clientX / window.innerWidth - 0.5;
  parallax.y = event.clientY / window.innerHeight - 0.5;
}

onMounted(async () => {
  parallaxEnabled =
    !window.matchMedia("(prefers-reduced-motion: reduce)").matches &&
    window.matchMedia("(pointer: fine)").matches;
  if (parallaxEnabled) window.addEventListener("mousemove", handleMouseMove);

  try {
    const [status, registerStatus] = await Promise.all([
      getAdminSetupStatus(),
      getRegistrationStatus().catch(() => false)
    ]);
    if (!mounted.value) return;
    setupRequired.value = status.required;
    setupEnabled.value = status.enabled;
    if (status.required) mode.value = "setup";
    registrationEnabled.value = registerStatus;
  } catch {
    // IAM 尚未启动时保留正常登录表单，不制造额外的全局报错。
  } finally {
    if (mounted.value) setupStatusLoading.value = false;
  }
});

onBeforeUnmount(() => {
  mounted.value = false;
  if (parallaxEnabled) window.removeEventListener("mousemove", handleMouseMove);
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

async function handleRegister(formEl: FormInstance | undefined) {
  if (!formEl || registerLoading.value || !registrationEnabled.value) return;
  registerLoading.value = true;
  try {
    if (!await formEl.validate().catch(() => false) || !mounted.value) return;
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
    if (!mounted.value) return;
    loginForm.username = username;
    loginForm.password = "";
    registerForm.password = "";
    registerForm.confirmPassword = "";
    mode.value = "login";
    message.success("注册成功，请登录；组织归属可由管理员稍后分配");
  } catch {
    // 请求层统一提示，失败保留输入。
  } finally {
    if (mounted.value) registerLoading.value = false;
  }
}
</script>

<template>
  <main
    class="login-page"
    :class="{ 'setup-mode': mode !== 'login' }"
    :style="`--px:${parallax.x};--py:${parallax.y}`"
  >
    <img class="login-wave" :src="backgroundUrl" alt="" aria-hidden="true" />

    <!-- 全屏氛围层：细网格 / 光晕 / 知识星图 / 水印 -->
    <div class="backdrop-grid" aria-hidden="true" />
    <div class="panel-glow glow-a" aria-hidden="true" />
    <div class="panel-glow glow-b" aria-hidden="true" />
    <div class="stage-watermark" aria-hidden="true">知</div>

    <div class="login-illustration constellation constellation-main" aria-hidden="true">
      <svg viewBox="0 0 560 220" fill="none" role="presentation" aria-hidden="true">
        <g class="links">
          <line x1="46" y1="118" x2="112" y2="58" pathLength="1" style="--d: .55s" />
          <line x1="46" y1="118" x2="126" y2="152" pathLength="1" style="--d: .6s" />
          <line x1="112" y1="58" x2="204" y2="102" pathLength="1" style="--d: .66s" />
          <line x1="126" y1="152" x2="204" y2="102" pathLength="1" style="--d: .62s" />
          <line x1="204" y1="102" x2="292" y2="48" pathLength="1" style="--d: .72s" />
          <line x1="204" y1="102" x2="312" y2="122" pathLength="1" style="--d: .68s" />
          <line x1="236" y1="172" x2="312" y2="122" pathLength="1" style="--d: .78s" />
          <line x1="292" y1="48" x2="382" y2="78" pathLength="1" style="--d: .82s" />
          <line x1="312" y1="122" x2="382" y2="78" pathLength="1" style="--d: .74s" />
          <line x1="312" y1="122" x2="404" y2="162" pathLength="1" style="--d: .8s" />
          <line x1="382" y1="78" x2="472" y2="108" pathLength="1" style="--d: .86s" />
          <line x1="404" y1="162" x2="472" y2="108" pathLength="1" style="--d: .84s" />
          <line x1="472" y1="108" x2="516" y2="48" pathLength="1" style="--d: .9s" />
          <line x1="472" y1="108" x2="524" y2="152" pathLength="1" style="--d: .88s" />
        </g>
        <path class="pulse-path" d="M46 118L126 152L204 102L312 122L382 78L472 108L524 152" pathLength="1" />
        <g class="nodes">
          <rect x="37" y="111" width="18" height="14" rx="3" style="--d: 0s" />
          <circle cx="112" cy="58" r="4.5" style="--d: -1.1s" />
          <circle cx="126" cy="152" r="4.5" style="--d: -2s" />
          <rect x="195" y="95" width="18" height="14" rx="3" style="--d: -.7s" />
          <circle cx="236" cy="172" r="4" style="--d: -2.6s" />
          <circle cx="292" cy="48" r="4.5" style="--d: -1.6s" />
          <rect x="303" y="115" width="18" height="14" rx="3" style="--d: -.3s" />
          <circle cx="382" cy="78" r="4.5" style="--d: -2.3s" />
          <circle cx="404" cy="162" r="4" style="--d: -1.4s" />
          <rect x="463" y="101" width="18" height="14" rx="3" style="--d: -1s" />
          <circle cx="516" cy="48" r="4" style="--d: -2.8s" />
          <circle cx="524" cy="152" r="4.5" style="--d: -1.9s" />
        </g>
        <circle class="pulse-halo" r="9">
          <animateMotion dur="7s" repeatCount="indefinite" path="M46 118L126 152L204 102L312 122L382 78L472 108L524 152" />
        </circle>
        <circle class="pulse-dot" r="3.5">
          <animateMotion dur="7s" repeatCount="indefinite" path="M46 118L126 152L204 102L312 122L382 78L472 108L524 152" />
        </circle>
      </svg>
    </div>
    <div class="constellation constellation-echo" aria-hidden="true">
      <svg viewBox="0 0 560 220" fill="none" role="presentation" aria-hidden="true">
        <g class="links">
          <line x1="46" y1="118" x2="112" y2="58" />
          <line x1="46" y1="118" x2="126" y2="152" />
          <line x1="112" y1="58" x2="204" y2="102" />
          <line x1="126" y1="152" x2="204" y2="102" />
          <line x1="204" y1="102" x2="312" y2="122" />
          <line x1="312" y1="122" x2="382" y2="78" />
          <line x1="312" y1="122" x2="404" y2="162" />
          <line x1="382" y1="78" x2="472" y2="108" />
          <line x1="404" y1="162" x2="472" y2="108" />
          <line x1="472" y1="108" x2="524" y2="152" />
        </g>
        <g class="nodes">
          <rect x="37" y="111" width="18" height="14" rx="3" style="--d: -1.8s" />
          <circle cx="112" cy="58" r="4.5" style="--d: -.5s" />
          <circle cx="126" cy="152" r="4.5" style="--d: -2.9s" />
          <rect x="195" y="95" width="18" height="14" rx="3" style="--d: -.9s" />
          <rect x="303" y="115" width="18" height="14" rx="3" style="--d: -2.2s" />
          <circle cx="382" cy="78" r="4.5" style="--d: -1.3s" />
          <circle cx="404" cy="162" r="4" style="--d: -2.5s" />
          <rect x="463" y="101" width="18" height="14" rx="3" style="--d: -.2s" />
          <circle cx="524" cy="152" r="4.5" style="--d: -1.7s" />
        </g>
      </svg>
    </div>

    <header class="login-masthead reveal" style="--d: 0s">
      <span class="masthead-brand"><i />DOCBASE</span>
      <span class="masthead-meta">ENTERPRISE KNOWLEDGE OS</span>
    </header>

    <section class="login-stage" aria-label="DocBase 账号入口">
      <div class="stage-copy">
        <p class="intro-eyebrow reveal" style="--d: .1s">知识，可被追问</p>
        <h2 class="intro-headline reveal" style="--d: .18s">让组织的文档<br />成为可检索、<br />可回答的知识<span>。</span></h2>
        <p class="intro-copy reveal" style="--d: .28s">文档入库、结构化索引、权限隔离与可追溯的 AI 回答，在同一个工作区完成。</p>
        <ul class="intro-features reveal" style="--d: .38s">
          <li><i /><div><strong>结构化入库</strong><span>解析、切分与索引自动完成</span></div></li>
          <li><i /><div><strong>可追溯问答</strong><span>每句回答都能定位到原文</span></div></li>
          <li><i /><div><strong>权限化治理</strong><span>组织、角色与菜单精细管控</span></div></li>
        </ul>
      </div>

      <div class="login-column">
        <div class="mobile-brand"><i />DOCBASE</div>
        <div class="login-form reveal" style="--d: .2s">
          <img class="brand-logo" :src="brandUrl" alt="DocBase" />
          <h1>DocBase</h1>
          <p class="login-subtitle">企业文档知识库管理平台</p>

          <div v-if="setupRequired || registrationEnabled" class="mode-switch" :class="{ triple: setupRequired && registrationEnabled }" aria-label="账号入口选择">
            <button type="button" :class="{ active: mode === 'login' }" @click="mode = 'login'">
              登录
            </button>
            <button v-if="registrationEnabled" type="button" :class="{ active: mode === 'register' }" @click="mode = 'register'">
              注册
            </button>
            <button v-if="setupRequired" type="button" :class="{ active: mode === 'setup' }" @click="mode = 'setup'">
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

          <el-form
            v-else-if="mode === 'register'"
            ref="registerFormRef"
            :model="registerForm"
            :rules="registerRules"
            size="large"
            @keyup.enter="handleRegister(registerFormRef)"
          >
            <p class="setup-hint">注册后可使用公开知识库与 AI 对话；组织和更多权限由管理员分配。</p>
            <el-form-item prop="username"><el-input v-model="registerForm.username" :prefix-icon="User" placeholder="用户名" autocomplete="username" /></el-form-item>
            <el-form-item prop="nickname"><el-input v-model="registerForm.nickname" :prefix-icon="User" placeholder="昵称" /></el-form-item>
            <el-form-item prop="email"><el-input v-model="registerForm.email" :prefix-icon="User" placeholder="邮箱（选填）" autocomplete="email" /></el-form-item>
            <el-form-item prop="password"><el-input v-model="registerForm.password" :prefix-icon="Lock" type="password" placeholder="密码（8～72 字节）" autocomplete="new-password" show-password /></el-form-item>
            <el-form-item prop="confirmPassword"><el-input v-model="registerForm.confirmPassword" :prefix-icon="Lock" type="password" placeholder="再次输入密码" autocomplete="new-password" show-password /></el-form-item>
            <el-button type="primary" class="login-button" :loading="registerLoading" @click="handleRegister(registerFormRef)">创建账号</el-button>
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
    <footer class="login-footer reveal" style="--d: .7s">Copyright © 2024-2026 DocBase All Rights Reserved.</footer>
  </main>
</template>

<style lang="scss" scoped>
/* 登录页：石墨全幕 + 知识星图背景 + 鼠标视差 + 悬浮暖纸登录卡 */
.login-page { position: relative; width: 100%; min-width: 320px; min-height: 100vh; min-height: 100dvh; display: flex; flex-direction: column; overflow: hidden; color: #f4f1e8; background: var(--docbase-graphite); }
.login-wave { display: none; }
.mobile-brand { display: none; }

/* ===== 全屏氛围层 ===== */
.backdrop-grid { position: absolute; inset: 0; opacity: .5; pointer-events: none; background-image: linear-gradient(rgba(244, 241, 232, .035) 1px, transparent 1px), linear-gradient(90deg, rgba(244, 241, 232, .035) 1px, transparent 1px); background-size: 64px 64px; mask-image: radial-gradient(circle at 30% 36%, #000 0%, transparent 78%); }
.panel-glow { position: absolute; border-radius: 50%; filter: blur(70px); pointer-events: none; }
.glow-a { top: -180px; right: -120px; width: 480px; height: 480px; background: radial-gradient(circle, rgba(194, 143, 28, 0.17), transparent 65%); animation: drift-a 16s ease-in-out infinite alternate; }
.glow-b { bottom: -160px; left: -140px; width: 420px; height: 420px; background: radial-gradient(circle, rgba(244, 241, 232, 0.07), transparent 62%); animation: drift-b 21s ease-in-out infinite alternate; }
.stage-watermark { position: absolute; bottom: -9%; left: -3%; color: transparent; font-size: min(42vw, 520px); font-weight: 700; line-height: 1; pointer-events: none; user-select: none; -webkit-text-stroke: 1px rgba(244, 241, 232, 0.05); }

/* ===== 知识星图：主层（右下，含脉冲） + 回声层（左上，静态） ===== */
.constellation { position: absolute; pointer-events: none; user-select: none; }
.constellation svg { display: block; width: 100%; height: auto; }
.constellation-main { right: -60px; bottom: -30px; width: min(58vw, 880px); opacity: .9; transform: translate(calc(var(--px, 0) * -16px), calc(var(--py, 0) * -12px)); transition: transform .55s cubic-bezier(.2, .6, .3, 1); animation: constellation-in 1s .45s ease both; }
.constellation-echo { top: 64px; left: -110px; width: min(34vw, 460px); opacity: .32; transform: rotate(180deg); animation: docbase-fade-local 1.2s .8s ease both; }
.constellation-echo .links line { stroke: var(--docbase-graphite-line); stroke-width: 1; }
.constellation-echo .nodes rect { fill: #e8e3d3; animation: node-breathe 3.4s ease-in-out infinite alternate; animation-delay: var(--d, 0s); }
.constellation-echo .nodes circle { fill: var(--docbase-graphite-muted); animation: node-breathe 3.4s ease-in-out infinite alternate; animation-delay: var(--d, 0s); }
.constellation-main .links line { stroke: var(--docbase-graphite-line); stroke-width: 1; stroke-dasharray: 1; stroke-dashoffset: 1; animation: link-draw .9s ease forwards; animation-delay: var(--d, .6s); }
.constellation-main .pulse-path { stroke: rgba(194, 143, 28, 0.4); stroke-width: 1; stroke-dasharray: 1; stroke-dashoffset: 1; animation: link-draw 1.3s .75s ease forwards; }
.constellation-main .nodes rect { fill: #e8e3d3; animation: node-breathe 3.4s ease-in-out infinite alternate; animation-delay: var(--d, 0s); }
.constellation-main .nodes circle { fill: var(--docbase-graphite-muted); animation: node-breathe 3.4s ease-in-out infinite alternate; animation-delay: var(--d, 0s); }
.pulse-dot { fill: var(--docbase-amber); }
.pulse-halo { fill: var(--docbase-amber); opacity: .16; }

/* ===== 顶部通栏 ===== */
.login-masthead { position: relative; z-index: 3; display: flex; align-items: center; justify-content: space-between; padding: 28px 48px 0; }
.masthead-brand { display: inline-flex; align-items: center; gap: 9px; color: #f4f1e8; font-size: 12px; font-weight: 700; letter-spacing: 2.4px; }
.masthead-brand i { width: 9px; height: 9px; background: var(--docbase-amber); border-radius: 2px; }
.masthead-meta { color: var(--docbase-graphite-muted); font-size: 10px; font-weight: 600; letter-spacing: 1.6px; }

/* ===== 舞台：左排版 + 右浮卡 ===== */
.login-stage { position: relative; z-index: 2; flex: 1; display: grid; grid-template-columns: minmax(0, 1fr) 460px; gap: 40px; align-items: center; width: min(1280px, 100%); margin: 0 auto; padding: 24px 48px 32px; }
.stage-copy { min-width: 0; max-width: 560px; }
.intro-eyebrow { margin: 0 0 20px; color: var(--docbase-amber); font-size: 11px; font-weight: 700; letter-spacing: 3px; }
.intro-headline { margin: 0; color: #f4f1e8; font-size: clamp(30px, 3.4vw, 46px); font-weight: 700; line-height: 1.48; letter-spacing: -0.5px; }
.intro-headline span { color: var(--docbase-amber); }
.intro-copy { max-width: 430px; margin: 24px 0 0; color: var(--docbase-graphite-text); font-size: 13px; line-height: 1.9; }
.intro-features { display: flex; flex-direction: column; gap: 15px; margin: 34px 0 0; padding: 0; list-style: none; }
.intro-features li { display: flex; align-items: flex-start; gap: 12px; }
.intro-features i { flex: none; width: 7px; height: 7px; margin-top: 6px; background: var(--docbase-amber); border-radius: 2px; }
.intro-features strong { display: block; color: #f4f1e8; font-size: 13px; font-weight: 650; letter-spacing: .02em; }
.intro-features span { color: var(--docbase-graphite-muted); font-size: 12px; line-height: 1.6; }

.login-column { display: flex; flex-direction: column; align-items: flex-end; justify-content: center; min-width: 0; }
.login-form { width: 392px; max-width: 100%; padding: 36px 38px 30px; color: var(--docbase-ink); text-align: left; background: var(--docbase-surface); border: 1px solid rgba(244, 241, 232, 0.16); border-radius: 18px; box-shadow: 0 40px 90px rgba(10, 8, 5, 0.55); }
.brand-logo { display: block; width: 44px; height: 44px; margin: 0 0 16px; padding: 7px; background: var(--docbase-graphite); border-radius: 11px; }
h1 { margin: 0; color: var(--docbase-ink); font-size: 30px; font-weight: 720; line-height: 1.25; letter-spacing: -1px; }
h1::after { content: "."; color: var(--docbase-amber); }
.login-subtitle { margin: 8px 0 28px; color: var(--docbase-muted); font-size: 13px; line-height: 1.6; }
.mode-switch { display: grid; grid-template-columns: repeat(2, 1fr); gap: 5px; margin: -8px 0 24px; padding: 4px; background: var(--docbase-surface-soft); border: 1px solid var(--docbase-line-soft); border-radius: 10px; }
.mode-switch.triple { grid-template-columns: repeat(3, 1fr); }
.mode-switch button { padding: 9px 6px; color: var(--docbase-muted); font: inherit; font-size: 12px; background: transparent; border: 0; border-radius: 7px; cursor: pointer; }
.mode-switch button.active { color: var(--docbase-ink); font-weight: 650; background: var(--docbase-surface); box-shadow: 0 1px 4px rgba(36, 33, 26, 0.12); }
.setup-hint { margin: -4px 0 16px; color: var(--docbase-muted); font-size: 12px; line-height: 1.7; }
.setup-alert { margin: -4px 0 16px; text-align: left; }
.login-options { display: flex; align-items: center; justify-content: space-between; height: 28px; margin: -2px 0 20px; }
.forgot-password.is-disabled { color: var(--docbase-subtle); }
.login-button { width: 100%; height: 44px; font-size: 14px; letter-spacing: .08em; border-radius: 8px; transition: transform .18s ease, box-shadow .18s ease, background-color .18s ease; }
.login-button:not(.is-disabled):not(.is-loading):hover { transform: translateY(-1px); }
.setup-button { margin-top: 2px; }
:deep(.el-form-item) { margin-bottom: 19px; }
:deep(.el-input__wrapper) { min-height: 44px; padding: 1px 14px; background: #fff; border-radius: 8px; box-shadow: 0 0 0 1px var(--docbase-line) inset; transition: box-shadow .2s ease; }
:deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #b3ac99 inset; }
:deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 2px var(--docbase-accent) inset; }

.login-footer { position: relative; z-index: 3; padding: 0 48px 22px; color: var(--docbase-graphite-muted); font-size: 10px; letter-spacing: .04em; }

/* ===== 动效 ===== */
.reveal { animation: rise .64s cubic-bezier(.22, .61, .36, 1) backwards; animation-delay: var(--d, 0s); }
@keyframes rise { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: translateY(0); } }
@keyframes link-draw { to { stroke-dashoffset: 0; } }
@keyframes node-breathe { from { opacity: .4; } to { opacity: .95; } }
@keyframes drift-a { to { transform: translate(-48px, 36px); } }
@keyframes drift-b { to { transform: translate(42px, -30px); } }
@keyframes constellation-in { from { opacity: 0; transform: translate(24px, 12px); } to { opacity: .9; } }
@keyframes docbase-fade-local { from { opacity: 0; } to { opacity: .32; } }

/* ===== 响应式 ===== */
@media (max-width: 1100px) {
  .login-stage { grid-template-columns: minmax(0, 1fr) 400px; padding: 20px 32px 28px; }
  .login-masthead { padding: 24px 32px 0; }
  .intro-features span { display: none; }
  .constellation-main { width: 62vw; right: -120px; }
}
@media (max-width: 880px) {
  .stage-copy, .stage-watermark, .constellation-echo { display: none; }
  .constellation-main { right: 50%; bottom: auto; top: 46%; width: 560px; max-width: 130vw; opacity: .3; transform: translateX(50%); transition: none; }
  .mobile-brand { display: inline-flex; align-items: center; gap: 9px; margin: 0 auto 18px; color: #f4f1e8; font-size: 12px; font-weight: 700; letter-spacing: 2.4px; }
  .mobile-brand i { width: 9px; height: 9px; background: var(--docbase-amber); border-radius: 2px; }
  .login-stage { grid-template-columns: 1fr; padding: 18px 22px 28px; }
  .login-column { align-items: center; }
  .login-form { width: 100%; max-width: 420px; }
  .login-footer { padding: 0 24px 18px; text-align: center; }
}
@media (max-width: 480px) {
  .login-masthead { padding: 22px 24px 0; }
  .masthead-meta { display: none; }
  .login-form { padding: 28px 24px 26px; }
  h1 { font-size: 27px; }
}
@media (max-height: 820px), (max-width: 880px) and (max-height: 720px) { .login-page.setup-mode { overflow-x: hidden; overflow-y: auto; } .setup-mode .login-stage { flex: none; } .setup-mode .login-column { align-items: flex-start; } }
@media (max-width: 880px) and (max-height: 620px) { .login-page { overflow-x: hidden; overflow-y: auto; } .login-stage { flex: none; } .login-column { align-items: flex-start; } }
@media (prefers-reduced-motion: reduce) {
  .reveal { opacity: 1; animation: none; }
  .constellation-main, .constellation-echo { animation: none; transform: none; transition: none; }
  .constellation-echo { opacity: .32; }
  .constellation-main { opacity: .9; }
  .links line, .pulse-path { stroke-dashoffset: 0; animation: none !important; }
  .nodes rect, .nodes circle, .panel-glow { animation: none; }
  .login-button, :deep(.el-input__wrapper) { transition: none; }
}
</style>
