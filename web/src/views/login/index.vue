<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { User, Lock } from "@element-plus/icons-vue";
import { loginApi } from "@/api/auth";
import { setLoginResult } from "@/utils/auth";
import { useUserStoreHook } from "@/store/modules/user";
import { message } from "@/utils/message";

const router = useRouter();
const route = useRoute();
const user = useUserStoreHook();

const loginFormRef = ref<FormInstance>();
const loading = ref(false);

const loginForm = reactive({
  username: "admin",
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
    // 密码明文经 HTTPS 发送，后端 BCrypt 比较
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

    message.success("登录成功");
    const redirect = (route.query.redirect as string) || "/home";
    router.push(redirect);
  } catch {
    // 错误提示已由请求层处理
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-title">
        <h2>DocBase Microservices</h2>
        <p class="login-subtitle">企业知识库 · 统一登录</p>
      </div>

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

        <el-form-item>
          <el-button
            type="primary"
            class="login-btn"
            :loading="loading"
            @click="handleLogin(loginFormRef)"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <p class="login-tip">
        提示：通过 Gateway（/api）统一认证，账号由 IAM 管理。
      </p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.login-container {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-box {
  width: 380px;
  padding: 40px 32px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.18);
}

.login-title {
  text-align: center;
  margin-bottom: 28px;

  h2 {
    font-size: 22px;
    margin: 0 0 6px;
    color: #303133;
  }
}

.login-subtitle {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.login-btn {
  width: 100%;
}

.login-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}
</style>
