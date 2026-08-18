<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { Lock, User } from "@element-plus/icons-vue";
import { loginApi } from "@/api/auth";
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

const rememberedUsername = localStorage.getItem(REMEMBERED_USERNAME_KEY) || "";
const loginFormRef = ref<FormInstance>();
const loading = ref(false);
const rememberUsername = ref(Boolean(rememberedUsername));

const loginForm = reactive({
  username: rememberedUsername || "admin",
  password: ""
});

const loginRules: FormRules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [
    { required: true, message: "请输入密码", trigger: "blur" },
    { min: 6, message: "密码至少 6 位", trigger: "blur" }
  ]
};

async function handleLogin(formEl: FormInstance | undefined) {
  if (!formEl) return;
  const valid = await formEl.validate().catch(() => false);
  if (!valid) return;

  loading.value = true;
  try {
    const res = await loginApi({
      username: loginForm.username,
      password: loginForm.password
    });

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
    const redirect = (route.query.redirect as string) || "/home";
    router.push(redirect);
  } catch {
    // 错误提示由请求层统一处理，保留当前输入供用户修正。
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <img class="login-wave" :src="backgroundUrl" alt="" aria-hidden="true" />

    <section class="login-container" aria-label="DocBase 登录">
      <div class="illustration-column" aria-hidden="true">
        <img class="login-illustration" :src="illustrationUrl" alt="" />
      </div>

      <div class="login-column">
        <div class="login-form">
          <img class="brand-logo" :src="brandUrl" alt="DocBase" />
          <h1>DocBase</h1>
          <p class="login-subtitle">企业文档知识库管理平台</p>

          <el-form
            ref="loginFormRef"
            :model="loginForm"
            :rules="loginRules"
            size="large"
            @keyup.enter="handleLogin(loginFormRef)"
          >
            <el-form-item prop="username">
              <el-input
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

            <div class="login-options">
              <el-checkbox v-model="rememberUsername">记住账号</el-checkbox>
              <el-button
                class="forgot-password"
                type="primary"
                link
                disabled
                title="请联系系统管理员重置密码"
              >
                忘记密码
              </el-button>
            </div>

            <el-button
              type="primary"
              class="login-button"
              :loading="loading"
              @click="handleLogin(loginFormRef)"
            >
              登录
            </el-button>
          </el-form>
        </div>
      </div>
    </section>

    <footer class="login-footer">
      Copyright © 2024-2026 DocBase All Rights Reserved.
    </footer>
  </main>
</template>

<style lang="scss" scoped>
.login-page {
  position: relative;
  width: 100%;
  min-width: 320px;
  min-height: 100vh;
  min-height: 100dvh;
  overflow: hidden;
  color: #303133;
  background: #fff;
}

.login-wave {
  position: fixed;
  bottom: 0;
  left: 0;
  z-index: 0;
  width: auto;
  height: 100%;
  pointer-events: none;
  user-select: none;
}

.login-container {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(360px, 1fr);
  gap: clamp(5rem, 12vw, 18rem);
  width: 100%;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 0 clamp(2rem, 5vw, 5rem);
}

.illustration-column,
.login-column {
  display: flex;
  align-items: center;
}

.illustration-column {
  justify-content: flex-end;
}

.login-illustration {
  display: block;
  width: min(500px, 42vw);
  height: auto;
  user-select: none;
  animation: illustration-enter 0.6s ease-out both;
}

.login-column {
  justify-content: flex-start;
  min-width: 0;
  text-align: center;
}

.login-form {
  width: 360px;
  max-width: 100%;
  animation: form-enter 0.5s 0.08s ease-out both;
}

.brand-logo {
  display: block;
  width: 66px;
  height: 66px;
  margin: 0 auto 12px;
}

h1 {
  margin: 0;
  color: #606266;
  font-family: Consolas, Monaco, "Courier New", monospace;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 0.04em;
}

.login-subtitle {
  margin: 10px 0 28px;
  color: #909399;
  font-size: 14px;
  line-height: 1.6;
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 28px;
  margin: -2px 0 18px;
}

.forgot-password.is-disabled {
  color: #a8abb2;
}

.login-button {
  width: 100%;
  height: 42px;
  font-size: 15px;
  letter-spacing: 0.16em;
  border-radius: 4px;
}

:deep(.el-form-item) {
  margin-bottom: 20px;
}

:deep(.el-input__wrapper) {
  min-height: 44px;
  border-radius: 4px;
  box-shadow: 0 0 0 1px #dcdfe6 inset;
  transition:
    box-shadow 0.2s ease,
    transform 0.2s ease;
}

:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #a8abb2 inset;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
  transform: translateY(-1px);
}

.login-footer {
  position: absolute;
  right: 24px;
  bottom: 20px;
  z-index: 2;
  color: #a8abb2;
  font-size: 12px;
  line-height: 1.5;
}

@keyframes illustration-enter {
  from {
    opacity: 0;
    transform: translateX(-18px);
  }

  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes form-enter {
  from {
    opacity: 0;
    transform: translateY(14px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1180px) {
  .login-container {
    gap: clamp(3rem, 8vw, 9rem);
  }

  .login-form {
    width: 320px;
  }
}

@media (max-width: 968px) {
  .login-wave,
  .illustration-column {
    display: none;
  }

  .login-container {
    grid-template-columns: 1fr;
    padding: 0 28px;
  }

  .login-column {
    justify-content: center;
  }

  .login-form {
    width: 100%;
    max-width: 360px;
  }

  .login-footer {
    right: 20px;
    left: 20px;
    text-align: center;
  }
}

@media (max-width: 480px) {
  .login-container {
    padding: 0 24px 34px;
  }

  .brand-logo {
    width: 58px;
    height: 58px;
  }

  h1 {
    font-size: 28px;
  }

  .login-subtitle {
    margin-bottom: 24px;
  }
}

@media (max-width: 968px) and (max-height: 620px) {
  .login-page {
    overflow-x: hidden;
    overflow-y: auto;
  }

  .login-container {
    min-height: auto;
    padding-top: 28px;
    padding-bottom: 24px;
  }

  .login-column {
    align-items: flex-start;
  }

  .login-footer {
    position: static;
    margin: 0 20px 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-illustration,
  .login-form {
    animation: none;
  }

  :deep(.el-input__wrapper) {
    transition: none;
  }
}
</style>
