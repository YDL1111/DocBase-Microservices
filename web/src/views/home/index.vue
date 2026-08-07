<script setup lang="ts">
import { computed } from "vue";
import { useUserStoreHook } from "@/store/modules/user";

const user = useUserStoreHook();
const displayName = computed(() => user.displayName);
</script>

<template>
  <div class="home-container">
    <el-card>
      <template #header>
        <span>欢迎回来</span>
      </template>
      <p>你好，<strong>{{ displayName }}</strong>。</p>
      <p class="tip">
        DocBase 微服务前端 Phase 1 已就绪：登录 / 登出 / Token 刷新 / 动态菜单 /
        路由守卫 / 按钮权限 均已接入。业务页面将在后续阶段迁移。
      </p>
    </el-card>

    <el-card class="info-card">
      <template #header>
        <span>v-auth 按钮权限演示</span>
      </template>
      <el-space>
        <el-button v-auth="'system:user:create'">新增用户（需权限码）</el-button>
        <el-button v-auth="'system:user:edit'">编辑用户（需权限码）</el-button>
        <el-button>普通按钮（无需权限）</el-button>
      </el-space>
      <p class="tip">
        提示：当前账号若未拥有对应 permission code，上述按钮会被自动移除。
      </p>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.home-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.info-card {
  margin-top: 8px;
}

.tip {
  margin-top: 12px;
  font-size: 13px;
  color: #909399;
}
</style>
